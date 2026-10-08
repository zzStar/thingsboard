#!/usr/bin/env bash
#
# SPDX-FileCopyrightText: Copyright ThingsBoard, Inc.
# SPDX-License-Identifier: BUSL-1.1
#

set -euo pipefail
BASE=/opt/zhigong-things
ACTION=${1:-status}
VERSION=${2:-}
FROM_VERSION=${3:-}
DOMAIN=things.zhigongshulian.com
REPO=registry.cn-hangzhou.aliyuncs.com/zgsl/things
fail() { echo "ERROR: $*" >&2; exit 1; }
case "$ACTION" in
  status|logs)
    [[ -f "$BASE/current" && -f "$BASE/.env" ]] || fail 'No deployed release.'
    source "$BASE/current"
    RELEASE="$BASE/releases/$VERSION"
    export APP_IMAGE DOMAIN
    if [[ "$ACTION" == status ]]; then
      echo "Release: $VERSION, image: $APP_IMAGE"
      docker compose --env-file "$BASE/.env" -f "$RELEASE/compose.yml" ps
    else
      docker compose --env-file "$BASE/.env" -f "$RELEASE/compose.yml" logs --tail 100 -f thingsboard
    fi
    exit ;;
  deploy|rollback|backup) ;;
  *) fail 'Unknown action' ;;
esac
mkdir -p "$BASE/backups" "$BASE/releases"
exec 9>"$BASE/deploy.lock"
flock -n 9 || fail 'Another deployment or backup is running.'
umask 077
if [[ "$ACTION" == backup ]]; then
  [[ -f "$BASE/current" ]] || fail 'No deployed release.'
  source "$BASE/current"
else
  [[ "$VERSION" =~ ^[a-zA-Z0-9][a-zA-Z0-9_.-]{0,100}$ ]] || fail 'Invalid VERSION'
fi
RELEASE="$BASE/releases/$VERSION"
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
export DOMAIN
if [[ "$ACTION" != backup ]]; then
  APP_IMAGE="$REPO:$VERSION"
  if [[ "$ACTION" == rollback ]]; then
    [[ -f "$RELEASE/release.env" ]] || fail 'Rollback requires a previously verified release'
    APP_IMAGE=$(bash -c 'source "$1"; printf "%s" "$APP_IMAGE"' _ "$RELEASE/release.env")
    [[ "$APP_IMAGE" == "$REPO@sha256:"* ]] || fail 'Invalid rollback digest'
  fi
fi
export APP_IMAGE
dc() { docker compose --env-file "$BASE/.env" -f "$RELEASE/compose.yml" "$@"; }
ready() {
  local started=$SECONDS state
  while ((SECONDS - started < 300)); do
    state=$(curl --noproxy '*' -fsS --max-time 3 http://127.0.0.1:18080/api/noauth/setup/state 2>/dev/null || true)
    if [[ "$state" == *READY* || "$state" == *ACCOUNT_REQUIRED* ]]; then return 0; fi
    if [[ "$state" == *LICENSE_REQUIRED* ]]; then return 1; fi
    sleep 3
  done
  return 1
}
backup() {
  local file="$BASE/backups/$(date -u +%Y%m%dT%H%M%SZ)-${OLD_VERSION:-$VERSION}.dump"
  dc exec -T postgres pg_dump -U postgres -d thingsboard -Fc > "$file.tmp"
  [[ -s "$file.tmp" ]] || fail 'Empty database backup'
  dc exec -T postgres pg_restore --list < "$file.tmp" >/dev/null
  mv "$file.tmp" "$file"
  sha256sum "$file" > "$file.sha256"
  [[ ! -f "$BASE/current" ]] || cp "$BASE/current" "$file.release"
  echo "Backup: $file"
}
if [[ "$ACTION" == backup ]]; then
  backup
  exit
fi
echo "Pulling $APP_IMAGE"
dc pull thingsboard postgres caddy
TAG_IMAGE=$APP_IMAGE
APP_IMAGE=$(docker image inspect "$TAG_IMAGE" --format '{{index .RepoDigests 0}}')
[[ "$APP_IMAGE" == "$REPO@sha256:"* ]] || fail 'Cannot pin image digest'
EXPECTED=$(docker image inspect "$APP_IMAGE" --format '{{index .Config.Labels "com.zhigong.schema"}}')
[[ "$EXPECTED" =~ ^[0-9]+$ ]] || fail 'Image is missing schema label'
export APP_IMAGE
dc config --quiet
dc up -d --wait --wait-timeout 180 postgres
TABLES=$(dc exec -T postgres psql -U postgres -d thingsboard -Atc "SELECT count(*) FROM information_schema.tables WHERE table_schema='public';")
MIGRATED=false
APP_STOPPED=false
SUCCESS=false
recover() {
  local result=$?
  trap - EXIT INT TERM
  if [[ "$SUCCESS" != true && "$APP_STOPPED" == true && -n "$OLD_IMAGE" && "$MIGRATED" == false ]]; then
    echo 'Deployment failed; restoring previous application image.' >&2
    APP_IMAGE=$OLD_IMAGE
    RELEASE=$OLD_RELEASE
    export APP_IMAGE
    if dc up -d thingsboard && ready && dc up -d caddy; then echo 'Previous application restored.' >&2; else echo 'Rollback failed: inspect server logs.' >&2; fi
  fi
  exit "$result"
}
trap recover EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
if [[ "$TABLES" == 0 ]]; then
  [[ "$ACTION" != rollback ]] || fail 'Cannot rollback an empty database'
  dc run --rm --no-deps thingsboard install
else
  SCHEMA_EXISTS=$(dc exec -T postgres psql -U postgres -d thingsboard -Atc "SELECT to_regclass('public.tb_schema_settings') IS NOT NULL;")
  [[ "$SCHEMA_EXISTS" == t ]] || fail 'Database contains tables but no schema marker; inspect failed installation.'
  SCHEMA=$(dc exec -T postgres psql -U postgres -d thingsboard -Atc 'SELECT schema_version FROM tb_schema_settings;')
  if [[ "$SCHEMA" != "$EXPECTED" ]]; then
    [[ "$ACTION" == deploy && -n "$FROM_VERSION" ]] || fail "Schema $SCHEMA differs from image $EXPECTED. Supply validated FROM_VERSION for upgrade."
    [[ "$FROM_VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+(\.[0-9]+)?$ ]] || fail 'Invalid FROM_VERSION'
    IFS=. read -r major minor maintenance patch <<< "$FROM_VERSION"
    patch=${patch:-0}
    FROM_SCHEMA=$((10#$major * 1000000000 + 10#$minor * 1000000 + 10#$maintenance * 1000 + 10#$patch))
    [[ "$FROM_SCHEMA" == "$SCHEMA" ]] || fail 'FROM_VERSION does not match database'
    ((EXPECTED > SCHEMA)) || fail 'Automatic schema downgrade is prohibited'
  fi
  dc stop thingsboard
  APP_STOPPED=true
  backup
  if [[ "$SCHEMA" != "$EXPECTED" ]]; then
    MIGRATED=true
    dc run --rm --no-deps -e "FROM_VERSION=$FROM_VERSION" thingsboard upgrade
  fi
fi
ACTUAL_SCHEMA=$(dc exec -T postgres psql -U postgres -d thingsboard -Atc 'SELECT schema_version FROM tb_schema_settings;')
[[ "$ACTUAL_SCHEMA" == "$EXPECTED" ]] || fail "Database schema $ACTUAL_SCHEMA does not match image $EXPECTED"
echo 'Starting application…'
dc up -d thingsboard
if ! ready; then
  dc logs --tail 80 thingsboard >&2
  fail 'Application did not become ready. After migrations, inspect backups before restoring.'
fi
dc up -d caddy
echo 'Verifying HTTPS…'
HTTPS_OK=false
for attempt in $(seq 1 60); do
  STATE=$(curl --noproxy '*' -fsS --max-time 5 "https://$DOMAIN/api/noauth/setup/state" 2>/dev/null || true)
  if [[ "$STATE" == *READY* || "$STATE" == *ACCOUNT_REQUIRED* ]]; then HTTPS_OK=true; break; fi
  sleep 3
done
[[ "$HTTPS_OK" == true ]] || fail 'HTTPS check failed; inspect DNS, ports and Caddy logs.'
[[ ! -f "$BASE/current" ]] || cp "$BASE/current" "$BASE/previous"
printf 'VERSION=%q\nAPP_IMAGE=%q\nSCHEMA=%q\nDEPLOYED_AT=%q\n' \
  "$VERSION" "$APP_IMAGE" "$EXPECTED" "$(date -u +%FT%TZ)" > "$BASE/current.tmp"
mv "$BASE/current.tmp" "$BASE/current"
cp "$BASE/current" "$RELEASE/release.env"
SUCCESS=true
echo "Deployment verified: https://${DOMAIN}, version ${VERSION}"
