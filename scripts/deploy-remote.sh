#!/usr/bin/env bash
#
# SPDX-FileCopyrightText: Copyright ThingsBoard, Inc.
# SPDX-License-Identifier: BUSL-1.1
#

set -euo pipefail
BASE=${TB_DEPLOY_BASE:-/opt/zhigong-things}
ACTION=${1:-status}
VERSION=${2:-}
FROM_VERSION=${3:-}
MODE=${4:-single}
DOMAIN=${TB_DEPLOY_DOMAIN:-things.zhigongshulian.com}
REPO=${TB_DEPLOY_REPO:-registry.cn-hangzhou.aliyuncs.com/zgsl/things}
HTTP_PORT=${TB_DEPLOY_HTTP_PORT:-18080}
READY_TIMEOUT=${TB_DEPLOY_READY_TIMEOUT:-300}
OBSERVE_SECONDS=${TB_DEPLOY_OBSERVE_SECONDS:-30}
PUBLIC_TIMEOUT=${TB_DEPLOY_PUBLIC_TIMEOUT:-90}
POLICY_TOOL=$(cd "$(dirname "$0")" && pwd)/deploy-policy.py
CADDY_CONFIG_DIR="$BASE/proxy"
export DOMAIN CADDY_CONFIG_DIR
fail() { echo "ERROR: $*" >&2; exit 1; }
load_current() {
  [[ -f "$BASE/current" && -f "$BASE/.env" ]] || fail 'No deployed release.'
  source "$BASE/current"
  RELEASE="$BASE/releases/$VERSION"
  export APP_IMAGE
}
dc() { docker compose --env-file "$BASE/.env" -f "$RELEASE/compose.yml" "$@"; }
old_dc() { APP_IMAGE="$OLD_IMAGE" docker compose --env-file "$BASE/.env" -f "$OLD_RELEASE/compose.yml" "$@"; }
setup_state() {
  curl --noproxy '*' -fsS --connect-timeout 2 --max-time 5 "$1" 2>/dev/null | python3 "$POLICY_TOOL" state 2>/dev/null
}
ready() {
  local started=$SECONDS state
  while ((SECONDS - started < READY_TIMEOUT)); do
    state=$(setup_state "http://127.0.0.1:$HTTP_PORT/api/noauth/setup/state" || true)
    if [[ "$state" == READY || "$FIRST_INSTALL" == true && "$state" == ACCOUNT_REQUIRED ]]; then return 0; fi
    [[ "$state" != LICENSE_REQUIRED && "$state" != NON_PRODUCTION_CONFIRMATION_REQUIRED ]] || return 1
    sleep 3
  done
  return 1
}
proxy_reload() {
  local template=$1
  [[ -f "$template" ]] || return 1
  # Validate and reload inside the already running proxy; its old release may bind a single file.
  # /config survives a container restart. The next successful release uses the stable directory mount.
  dc exec -T caddy sh -c 'mkdir -p /config/deploy; cat > /config/deploy/candidate; caddy validate --config /config/deploy/candidate --adapter caddyfile' < "$template" >/dev/null 2>&1 || return 1
  cp "$template" "$CADDY_CONFIG_DIR/Caddyfile.tmp" || return 1
  mv "$CADDY_CONFIG_DIR/Caddyfile.tmp" "$CADDY_CONFIG_DIR/Caddyfile" || return 1
  dc exec -T caddy caddy reload --config /config/deploy/candidate --adapter caddyfile >/dev/null 2>&1 || return 1
}
verify() {
  ready || return 1
  dc exec -T postgres psql -U postgres -d thingsboard -Atc 'SELECT 1;' >/dev/null || return 1
  if [[ -f "$RELEASE/verified-policy.json" ]]; then
    local expected_production actual_production
    expected_production=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["production_schema_version"])' "$RELEASE/verified-policy.json") || return 1
    actual_production=$(dc exec -T postgres psql -U postgres -d thingsboard -Atc 'SELECT schema_version FROM production_schema_settings WHERE id=1;') || return 1
    [[ "$expected_production" == "$actual_production" ]] || return 1
  fi
  curl --noproxy '*' -fsS --max-time 10 "http://127.0.0.1:$HTTP_PORT/" >/dev/null || return 1
  local state started=$SECONDS public_ready=false
  while ((SECONDS - started < PUBLIC_TIMEOUT)); do
    state=$(setup_state "https://$DOMAIN/api/noauth/setup/state" || true)
    if [[ "$state" == READY || "$FIRST_INSTALL" == true && "$state" == ACCOUNT_REQUIRED ]]; then public_ready=true; break; fi
    sleep 3
  done
  [[ "$public_ready" == true ]] || return 1
  curl --noproxy '*' -fsS --max-time 10 "https://$DOMAIN/" >/dev/null || return 1
  # A refused anonymous MQTT CONNECT still proves the public protocol endpoint, not telemetry persistence.
  python3 - "$DOMAIN" <<'PY'
import socket, sys
with socket.create_connection((sys.argv[1], 1883), 5) as conn:
    conn.settimeout(5)
    conn.sendall(b'\x10\x17\x00\x04MQTT\x04\x02\x00\x0a\x00\x0bzg-probe-v1')
    reply = b''
    while len(reply) < 4:
        part = conn.recv(4 - len(reply))
        if not part:
            break
        reply += part
    if len(reply) != 4 or reply[:2] != b'\x20\x02' or reply[3] > 5:
        sys.exit('MQTT protocol probe failed')
PY
}
case "$ACTION" in
  status|logs|verify)
    if [[ "$ACTION" == status ]]; then
      [[ ! -f "$BASE/deployment.state" ]] || cat "$BASE/deployment.state"
      if [[ -f "$BASE/jobs/active" ]]; then
        read -r job < "$BASE/jobs/active"
        echo "Job: $job, log: $BASE/jobs/$job/output.log"
        [[ ! -f "$BASE/jobs/$job/result" ]] || { printf 'Job exit code: '; cat "$BASE/jobs/$job/result"; }
      fi
      [[ -f "$BASE/current" ]] || { echo 'No committed release yet; inspect the job log above.'; exit; }
    fi
    load_current
    FIRST_INSTALL=false
    if [[ "$ACTION" == logs ]]; then dc logs --tail 100 -f thingsboard
    elif [[ "$ACTION" == verify ]]; then verify; echo 'HTTPS, database and MQTT protocol verified (no production write probe).'
    else
      echo "Release: $VERSION, image: $APP_IMAGE"
      dc ps
    fi
    exit ;;
  deploy|rollback|backup|plan) ;;
  *) fail 'Unknown action' ;;
esac
[[ "$MODE" == single || "$MODE" == maintenance ]] || fail 'MODE must be single or maintenance; rolling requires a validated cluster.'
[[ "$READY_TIMEOUT" =~ ^[0-9]+$ && "$OBSERVE_SECONDS" =~ ^[0-9]+$ && "$PUBLIC_TIMEOUT" =~ ^[0-9]+$ ]] || fail 'Invalid timeout'
umask 077
mkdir -p "$BASE/backups" "$BASE/releases" "$CADDY_CONFIG_DIR"
exec 9>"$BASE/deploy.lock"
flock -n 9 || fail 'Another deployment or backup is running.'
if [[ "$ACTION" == backup ]]; then load_current
else [[ "$VERSION" =~ ^[a-zA-Z0-9][a-zA-Z0-9_.-]{0,100}$ ]] || fail 'Invalid VERSION'; RELEASE="$BASE/releases/$VERSION"; fi
[[ -f "$RELEASE/compose.yml" ]] || fail 'Missing release configuration'
if [[ ! -f "$BASE/.env" ]]; then
  [[ "$ACTION" == deploy ]] || fail 'No deployment environment'
  PASSWORD=$(openssl rand -hex 24)
  printf 'POSTGRES_PASSWORD=%s\nDOMAIN=%s\n' "$PASSWORD" "$DOMAIN" > "$BASE/.env"
  chmod 600 "$BASE/.env"
fi
OLD_VERSION= OLD_IMAGE= OLD_RELEASE=
if [[ -f "$BASE/current" ]]; then
  OLD_VERSION=$(bash -c 'source "$1"; printf "%s" "$VERSION"' _ "$BASE/current")
  OLD_IMAGE=$(bash -c 'source "$1"; printf "%s" "$APP_IMAGE"' _ "$BASE/current")
  OLD_RELEASE="$BASE/releases/$OLD_VERSION"
fi
FIRST_INSTALL=false
[[ -n "$OLD_IMAGE" ]] || FIRST_INSTALL=true
BACKUP_FILE= PHASE=PREPARING APP_STOPPED=false NEW_STARTED=false MIGRATED=false SUCCESS=false PROXY_TOUCHED=false AUTO_ROLLBACK=false
PLAN_DIR=
JOURNAL="$BASE/deployment.state"
phase() {
  PHASE=$1
  printf 'PHASE=%q\nTARGET_VERSION=%q\nTARGET_IMAGE=%q\nOLD_VERSION=%q\nOLD_IMAGE=%q\nBACKUP_FILE=%q\nAPP_STOPPED=%q\nNEW_STARTED=%q\nMIGRATED=%q\nAUTO_ROLLBACK=%q\nPROXY_TOUCHED=%q\nRUN_ID=%q\nUPDATED_AT=%q\n' \
    "$PHASE" "$VERSION" "${APP_IMAGE:-}" "$OLD_VERSION" "$OLD_IMAGE" "$BACKUP_FILE" "$APP_STOPPED" "$NEW_STARTED" "$MIGRATED" "$AUTO_ROLLBACK" "$PROXY_TOUCHED" "${TB_DEPLOY_RUN_ID:-foreground}" "$(date -u +%FT%TZ)" > "$JOURNAL.tmp"
  mv "$JOURNAL.tmp" "$JOURNAL"
  echo "[$(date -u +%FT%TZ)] $PHASE version=$VERSION"
}
restore() {
  [[ -n "$OLD_IMAGE" && "$MIGRATED" == false ]] || return 1
  [[ "$NEW_STARTED" == false || "$AUTO_ROLLBACK" == true ]] || return 1
  phase RESTORING || return 1
  old_dc up -d --no-deps --pull never thingsboard || return 1
  FIRST_INSTALL=false
  ready || return 1
  old_dc up -d --no-deps --pull never caddy || return 1
  local saved_release=$RELEASE
  RELEASE=$OLD_RELEASE
  proxy_reload "$OLD_RELEASE/Caddyfile" || { RELEASE=$saved_release; return 1; }
  RELEASE=$saved_release
  APP_STOPPED=false PROXY_TOUCHED=false
  phase RESTORED
}
recover() {
  local result=$?
  trap - EXIT
  trap '' INT TERM HUP
  set +e
  [[ -z "$PLAN_DIR" ]] || rm -rf "$PLAN_DIR"
  if [[ "$SUCCESS" != true ]]; then
    if [[ "$APP_STOPPED" == true || "$PROXY_TOUCHED" == true ]]; then
      if restore; then echo 'Previous application restored; database preserved.' >&2
      else phase MANUAL_RECOVERY; echo "Automatic recovery unavailable/failed. Inspect $JOURNAL and $BACKUP_FILE; database was NOT restored." >&2; fi
    else phase FAILED; fi
  fi
  exit "$result"
}
# Reconcile a previous process killed before its EXIT handler completed, before doing another update.
if [[ "$ACTION" != backup && -f "$JOURNAL" ]]; then
  PRIOR_PHASE=$(bash -c 'source "$1"; printf "%s" "$PHASE"' _ "$JOURNAL")
  case "$PRIOR_PHASE" in
    COMMITTED|RESTORED|FAILED) ;;
    *)
      [[ "$ACTION" != plan ]] || fail 'An unfinished deployment exists; deploy-plan will not reconcile or change it. Inspect deploy-status.'
      # A committed current pointer wins if the process died while recording its final phase.
      PRIOR_TARGET=$(bash -c 'source "$1"; printf "%s" "$TARGET_VERSION"' _ "$JOURNAL")
      PRIOR_IMAGE=$(bash -c 'source "$1"; printf "%s" "$TARGET_IMAGE"' _ "$JOURNAL")
      if [[ "$PRIOR_PHASE" == COMMITTING && "$OLD_VERSION" == "$PRIOR_TARGET" && "$OLD_IMAGE" == "$PRIOR_IMAGE" ]]; then
        :
      else
        SAVED_VERSION=$VERSION SAVED_RELEASE=$RELEASE
        source "$JOURNAL"
        VERSION=$TARGET_VERSION RELEASE="$BASE/releases/$TARGET_VERSION" FIRST_INSTALL=false
        export APP_IMAGE="$TARGET_IMAGE"
        if [[ "$MIGRATED" == true || "$NEW_STARTED" == true && "$AUTO_ROLLBACK" != true ]]; then
          fail 'Previous deployment requires manual recovery; inspect deployment.state. No automatic database restore.'
        fi
        if [[ "$APP_STOPPED" == true || "$PROXY_TOUCHED" == true ]]; then
          OLD_RELEASE="$BASE/releases/$OLD_VERSION"
          restore || fail 'Interrupted deployment could not restore previous application'
        fi
        VERSION=$SAVED_VERSION RELEASE=$SAVED_RELEASE
        OLD_VERSION= OLD_IMAGE= OLD_RELEASE=
        if [[ -f "$BASE/current" ]]; then
          OLD_VERSION=$(bash -c 'source "$1"; printf "%s" "$VERSION"' _ "$BASE/current")
          OLD_IMAGE=$(bash -c 'source "$1"; printf "%s" "$APP_IMAGE"' _ "$BASE/current")
          OLD_RELEASE="$BASE/releases/$OLD_VERSION"
        else FIRST_INSTALL=true; fi
        APP_STOPPED=false NEW_STARTED=false MIGRATED=false PROXY_TOUCHED=false AUTO_ROLLBACK=false BACKUP_FILE=
      fi ;;
  esac
fi
backup() {
  local file="$BASE/backups/$(date -u +%Y%m%dT%H%M%SZ)-${OLD_VERSION:-$VERSION}-$$.dump"
  local bytes available
  bytes=$(dc exec -T postgres psql -U postgres -d thingsboard -Atc "SELECT pg_database_size('thingsboard');") || return 1
  [[ "$bytes" =~ ^[0-9]+$ ]] || return 1
  available=$(df -Pk "$BASE/backups" | awk 'NR==2 {print $4 * 1024}')
  # Conservative reserve: the database footprint plus 1 GiB, with no parallel pg_dump.
  awk -v free="$available" -v needed="$bytes" 'BEGIN {exit !(free > needed + 1073741824)}' || { echo 'Insufficient backup disk space' >&2; return 1; }
  dc exec -T -e PGOPTIONS='-c statement_timeout=1800000 -c lock_timeout=10000' postgres \
    pg_dump -U postgres -d thingsboard -Fc --lock-wait-timeout=10s > "$file.tmp" || return 1
  [[ -s "$file.tmp" ]] || return 1
  dc exec -T postgres pg_restore --list < "$file.tmp" >/dev/null || return 1
  mv "$file.tmp" "$file" || return 1
  sha256sum "$file" > "$file.sha256" || return 1
  [[ ! -f "$BASE/current" ]] || cp "$BASE/current" "$file.release" || return 1
  BACKUP_FILE=$file
  echo "Backup: $file (archive checked; full restore requires isolated verification)"
}
if [[ "$ACTION" == backup ]]; then backup; exit; fi
APP_IMAGE="$REPO:$VERSION"
if [[ "$ACTION" == rollback ]]; then
  [[ -f "$RELEASE/release.env" ]] || fail 'Rollback requires a verified release'
  APP_IMAGE=$(bash -c 'source "$1"; printf "%s" "$APP_IMAGE"' _ "$RELEASE/release.env")
fi
export APP_IMAGE
if [[ "$ACTION" != plan ]]; then
  trap recover EXIT
  trap 'exit 130' INT
  trap 'exit 143' TERM
  trap 'exit 129' HUP
  phase PREPARING
fi
[[ "$ACTION" == plan ]] || { echo "Pulling application $APP_IMAGE"; dc pull thingsboard; }
TAG_IMAGE=$APP_IMAGE
APP_IMAGE=$(docker image inspect "$TAG_IMAGE" --format '{{index .RepoDigests 0}}')
[[ "$APP_IMAGE" == "$REPO@sha256:"* ]] || fail 'Cannot pin image digest'
if [[ -f "$RELEASE/release.env" ]]; then
  RECORDED_IMAGE=$(bash -c 'source "$1"; printf "%s" "$APP_IMAGE"' _ "$RELEASE/release.env")
  [[ "$RECORDED_IMAGE" == "$APP_IMAGE" ]] || fail 'This release version already records another digest; use a new VERSION'
fi
EXPECTED=$(docker image inspect "$APP_IMAGE" --format '{{index .Config.Labels "com.zhigong.schema"}}')
[[ "$EXPECTED" =~ ^[0-9]+$ ]] || fail 'Missing schema label'
export APP_IMAGE
PLAN_DIR=$(mktemp -d "$BASE/plan.XXXXXX")
# Secrets in expanded Compose JSON are never printed and are deleted on completion.
cleanup_plan() { rm -rf "$PLAN_DIR"; }
[[ "$ACTION" != plan ]] || trap cleanup_plan EXIT
POLICY_HASH=$(docker image inspect "$APP_IMAGE" --format '{{index .Config.Labels "com.zhigong.release-policy"}}')
if [[ "$POLICY_HASH" =~ ^[a-f0-9]{64}$ ]]; then
  docker run --rm --network none --entrypoint cat "$APP_IMAGE" /app/release-policy.json > "$PLAN_DIR/policy.json"
elif [[ "$ACTION" == rollback && -f "$OLD_RELEASE/verified-policy.json" ]]; then
  # Only an explicitly reviewed legacy digest can be a rollback target without embedded metadata.
  cp "$OLD_RELEASE/verified-policy.json" "$PLAN_DIR/policy.json"
  POLICY_HASH=$(docker image inspect "$OLD_IMAGE" --format '{{index .Config.Labels "com.zhigong.release-policy"}}')
  python3 - "$PLAN_DIR/policy.json" "$APP_IMAGE" <<'PY'
import json,sys
policy=json.load(open(sys.argv[1]))
if sys.argv[2].split('@')[-1] not in policy.get('compatible_legacy_digests',[]):
    sys.exit('Unreviewed legacy rollback image; automatic downgrade is prohibited')
PY
else fail 'Image has no release policy. Rebuild with make deploy (BUILD=1).'; fi
[[ "$(sha256sum "$PLAN_DIR/policy.json" | awk '{print $1}')" == "$POLICY_HASH" ]] || fail 'Release policy digest mismatch'
dc config --quiet
dc config --format json > "$PLAN_DIR/new.json"
if [[ -n "$OLD_IMAGE" ]]; then
  old_dc config --format json > "$PLAN_DIR/old.json"
  python3 "$POLICY_TOOL" infrastructure "$PLAN_DIR/old.json" "$PLAN_DIR/new.json"
  docker image inspect "$OLD_IMAGE" > "$PLAN_DIR/old-image.json"
  COMPATIBILITY=$(python3 "$POLICY_TOOL" compatible "$PLAN_DIR/policy.json" "$OLD_IMAGE" < "$PLAN_DIR/old-image.json")
  [[ "$COMPATIBILITY" != compatible ]] || AUTO_ROLLBACK=true
  old_dc exec -T postgres pg_isready -h 127.0.0.1 -U postgres -d thingsboard >/dev/null || fail 'Existing database unavailable; application deploy will not recreate infrastructure'
  OLD_CONTAINER=$(old_dc ps -q thingsboard)
  [[ -n "$OLD_CONTAINER" ]] || fail 'Recorded application container missing; recover it before updating'
  ACTUAL_IMAGE=$(docker inspect "$OLD_CONTAINER" --format '{{.Config.Image}}')
  [[ "$ACTUAL_IMAGE" == "$OLD_IMAGE" ]] || fail 'Running application differs from current release; reconcile before updating'
else
  COMPATIBILITY=first-install
  [[ "$ACTION" == plan ]] || {
    dc pull postgres caddy
    dc up -d --wait --wait-timeout 180 postgres
    DB_READY=false
    for attempt in $(seq 1 60); do
      if dc exec -T postgres pg_isready -h 127.0.0.1 -U postgres -d thingsboard >/dev/null 2>&1; then DB_READY=true; break; fi
      sleep 2
    done
    [[ "$DB_READY" == true ]] || fail 'Database TCP listener did not become ready'
  }
fi
TABLES=$(dc exec -T postgres psql -U postgres -d thingsboard -Atc "SELECT count(*) FROM information_schema.tables WHERE table_schema='public';")
SCHEMA=
if [[ "$TABLES" != 0 ]]; then
  SCHEMA=$(dc exec -T postgres psql -U postgres -d thingsboard -Atc 'SELECT schema_version FROM tb_schema_settings;') || fail 'Nonempty database has no valid schema marker'
  PRODUCTION_SCHEMA_EXISTS=$(dc exec -T postgres psql -U postgres -d thingsboard -Atc "SELECT to_regclass('public.production_schema_settings') IS NOT NULL;")
  PRODUCTION_VERSION=0
  if [[ "$PRODUCTION_SCHEMA_EXISTS" == t ]]; then
    PRODUCTION_VERSION=$(dc exec -T postgres psql -U postgres -d thingsboard -Atc 'SELECT schema_version FROM production_schema_settings WHERE id=1;')
  fi
  python3 "$POLICY_TOOL" database "$PLAN_DIR/policy.json" "$PRODUCTION_VERSION"
  if [[ "$SCHEMA" != "$EXPECTED" ]]; then
    [[ "$ACTION" == deploy || "$ACTION" == plan ]] && [[ -n "$FROM_VERSION" ]] || fail 'Schema differs; provide a validated FROM_VERSION for maintenance upgrade'
    [[ "$FROM_VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+(\.[0-9]+)?$ ]] || fail 'Invalid FROM_VERSION'
    IFS=. read -r major minor maintenance patch <<< "$FROM_VERSION"; patch=${patch:-0}
    FROM_SCHEMA=$((10#$major * 1000000000 + 10#$minor * 1000000 + 10#$maintenance * 1000 + 10#$patch))
    [[ "$FROM_SCHEMA" == "$SCHEMA" ]] && ((EXPECTED > SCHEMA)) || fail 'Invalid upgrade path or schema downgrade'
    MODE=maintenance AUTO_ROLLBACK=false
  fi
  [[ "$COMPATIBILITY" == compatible ]] || MODE=maintenance
else
  [[ "$FIRST_INSTALL" == true && "$ACTION" == deploy ]] || fail 'Existing release has an empty database; refuse reinitialization'
fi
[[ "$ACTION" != rollback || "$AUTO_ROLLBACK" == true && "$SCHEMA" == "$EXPECTED" ]] || fail 'Rollback policy is incompatible or unknown'
echo "Plan: mode=$MODE compatibility=$COMPATIBILITY schema=${SCHEMA:-empty}->$EXPECTED auto-rollback=$AUTO_ROLLBACK"
if [[ "$ACTION" == plan ]]; then cleanup_plan; trap - EXIT; exit; fi
if [[ "$OLD_VERSION" == "$VERSION" && "$OLD_IMAGE" == "$APP_IMAGE" ]]; then
  # Idempotent retry after the atomic current pointer was committed.
  FIRST_INSTALL=false
  verify || fail 'Current release is unhealthy; inspect it before retrying'
  cleanup_plan
  trap - EXIT
  SUCCESS=true
  phase COMMITTED
  echo 'This exact release is already active and verified; no containers were replaced.'
  exit
fi
# Unknown legacy images use maintenance + final stopped backup and never speculative rollback after new writes.
cp "$PLAN_DIR/policy.json" "$RELEASE/verified-policy.json"
cleanup_plan
trap recover EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
trap 'exit 129' HUP
phase PREPARING
if [[ "$FIRST_INSTALL" == false ]]; then
  ready || fail 'Existing application is not READY; recover it before updating'
  if [[ "$MODE" == single ]]; then backup || fail 'Online backup failed; previous service remains running'; phase BACKED_UP; fi
  PROXY_TOUCHED=true
  phase DRAINING
  MAINT_TEMPLATE="$RELEASE/Caddyfile.maintenance"
  [[ -f "$MAINT_TEMPLATE" ]] || MAINT_TEMPLATE="$OLD_RELEASE/Caddyfile.maintenance"
  proxy_reload "$MAINT_TEMPLATE" || fail 'Cannot enter maintenance mode'
  # Journal intent before stop: an interrupted stop may already have stopped the application.
  APP_STOPPED=true
  phase STOPPING
  dc stop thingsboard
  phase STOPPED
  if [[ "$MODE" == maintenance ]]; then backup || fail 'Stopped backup failed'; phase BACKED_UP; fi
fi
if [[ "$TABLES" == 0 ]]; then
  MIGRATED=true
  phase INSTALLING
  dc run --rm --no-deps thingsboard install
elif [[ "$SCHEMA" != "$EXPECTED" ]]; then
  MIGRATED=true
  phase MIGRATION_STARTED
  dc run --rm --no-deps -e "FROM_VERSION=$FROM_VERSION" thingsboard upgrade
fi
ACTUAL_SCHEMA=$(dc exec -T postgres psql -U postgres -d thingsboard -Atc 'SELECT schema_version FROM tb_schema_settings;')
[[ "$ACTUAL_SCHEMA" == "$EXPECTED" ]] || fail 'Database schema does not match image'
NEW_STARTED=true
phase STARTING
dc up -d --no-deps --pull never thingsboard
ready || { dc logs --tail 80 thingsboard >&2; fail 'Application did not become ready'; }
phase VERIFYING
dc exec -T postgres psql -U postgres -d thingsboard -Atc 'SELECT 1;' >/dev/null
curl --noproxy '*' -fsS --max-time 10 "http://127.0.0.1:$HTTP_PORT/" >/dev/null
if [[ "$FIRST_INSTALL" == true ]]; then
  cp "$RELEASE/Caddyfile" "$CADDY_CONFIG_DIR/Caddyfile"
  dc up -d --no-deps --pull never caddy
else
  proxy_reload "$RELEASE/Caddyfile" || fail 'Cannot leave maintenance mode'
  # Only the first transition to a stable directory mount may recreate Caddy. No PostgreSQL restart.
  dc up -d --no-deps --pull never caddy
fi
verify || fail 'Public HTTPS or MQTT validation failed'
phase OBSERVING
OBSERVE_START=$SECONDS
while ((SECONDS - OBSERVE_START < OBSERVE_SECONDS)); do
  state=$(setup_state "https://$DOMAIN/api/noauth/setup/state" || true)
  [[ "$state" == READY || "$FIRST_INSTALL" == true && "$state" == ACCOUNT_REQUIRED ]] || fail 'Application failed during observation'
  sleep 3
done
phase COMMITTING
[[ ! -f "$BASE/current" ]] || cp "$BASE/current" "$BASE/previous.tmp"
printf 'VERSION=%q\nAPP_IMAGE=%q\nSCHEMA=%q\nDEPLOYED_AT=%q\n' "$VERSION" "$APP_IMAGE" "$EXPECTED" "$(date -u +%FT%TZ)" > "$RELEASE/release.env.tmp"
mv "$RELEASE/release.env.tmp" "$RELEASE/release.env"
cp "$RELEASE/release.env" "$BASE/current.tmp"
[[ ! -f "$BASE/previous.tmp" ]] || mv "$BASE/previous.tmp" "$BASE/previous"
mv "$BASE/current.tmp" "$BASE/current"
SUCCESS=true
phase COMMITTED
echo "Deployment verified: https://$DOMAIN, version $VERSION. MQTT protocol checked; device delivery depends on buffering/acknowledgement."
