#!/usr/bin/env bash
#
# SPDX-FileCopyrightText: Copyright ThingsBoard, Inc.
# SPDX-License-Identifier: BUSL-1.1
#

# Validate the exact amd64 image against an isolated, disposable database before publishing.
set -euo pipefail
IMAGE=${1:?image required}
SCHEMA=${2:?schema required}
ROOT=$(cd "$(dirname "$0")/.." && pwd)
mkdir -p "$ROOT/.dev"
NAME=zg-smoke-$(date +%s)-$$
ENV_FILE=$(mktemp "$ROOT/.dev/smoke-env.XXXXXX")
chmod 600 "$ENV_FILE"
LOG="$ROOT/.dev/$NAME.log"
cleanup() {
  result=$?
  trap - EXIT
  docker logs "$NAME-app" >> "$LOG" 2>&1 || true
  docker rm -f -v "$NAME-install" "$NAME-app" "$NAME-db" >/dev/null 2>&1 || true
  docker network rm "$NAME" >/dev/null 2>&1 || true
  docker volume rm "$NAME-data" >/dev/null 2>&1 || true
  rm -f "$ENV_FILE"
  if ((result != 0)); then echo "Image smoke test failed; log: $LOG" >&2; fi
  exit "$result"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
PASSWORD=$(openssl rand -hex 24)
printf 'POSTGRES_PASSWORD=%s\nPOSTGRES_DB=thingsboard\n' "$PASSWORD" > "$ENV_FILE"
printf 'SPRING_DATASOURCE_URL=jdbc:postgresql://postgres:5432/thingsboard\nSPRING_DATASOURCE_USERNAME=postgres\nSPRING_DATASOURCE_PASSWORD=%s\nTB_QUEUE_TYPE=in-memory\nJS_EVALUATOR=local\nJAVA_TOOL_OPTIONS=-Xms256m -Xmx1536m -XX:ActiveProcessorCount=2\n' "$PASSWORD" >> "$ENV_FILE"
docker network create "$NAME" >/dev/null
docker volume create "$NAME-data" >/dev/null
docker run -d --platform linux/amd64 --name "$NAME-db" --network "$NAME" --network-alias postgres \
  --env-file "$ENV_FILE" postgres:18.1 >/dev/null
DB_READY=false
for attempt in $(seq 1 60); do
  if docker exec "$NAME-db" pg_isready -h 127.0.0.1 -U postgres -d thingsboard >/dev/null 2>&1; then DB_READY=true; break; fi
  sleep 2
done
[[ "$DB_READY" == true ]]
docker run --platform linux/amd64 --name "$NAME-install" --network "$NAME" \
  --env-file "$ENV_FILE" -v "$NAME-data:/data" "$IMAGE" install > "$LOG" 2>&1
ACTUAL=$(docker exec "$NAME-db" psql -U postgres -d thingsboard -Atc 'SELECT schema_version FROM tb_schema_settings;')
[[ "$ACTUAL" == "$SCHEMA" ]]
docker run -d --platform linux/amd64 --name "$NAME-app" --network "$NAME" \
  --env-file "$ENV_FILE" -v "$NAME-data:/data" -p 127.0.0.1::1883 "$IMAGE" >/dev/null
READY=false
for attempt in $(seq 1 100); do
  STATE=$(docker exec "$NAME-app" curl --noproxy '*' -fsS --max-time 3 http://127.0.0.1:8080/api/noauth/setup/state 2>/dev/null || true)
  if [[ "$STATE" == *ACCOUNT_REQUIRED* || "$STATE" == *READY* ]]; then READY=true; break; fi
  [[ "$STATE" != *LICENSE_REQUIRED* ]]
  sleep 3
done
[[ "$READY" == true ]]
POLICY=$(docker run --rm --network none --entrypoint cat "$IMAGE" /app/release-policy.json)
EXPECTED_PRODUCTION=$(python3 -c 'import json,sys; print(json.loads(sys.argv[1])["production_schema_version"])' "$POLICY")
ACTUAL_PRODUCTION=$(docker exec "$NAME-db" psql -U postgres -d thingsboard -Atc 'SELECT schema_version FROM production_schema_settings WHERE id=1;')
[[ "$ACTUAL_PRODUCTION" == "$EXPECTED_PRODUCTION" ]]
docker exec "$NAME-app" curl --noproxy '*' -fsS --max-time 10 http://127.0.0.1:8080/ > "$ROOT/.dev/$NAME-page.html"
MQTT_PORT=$(docker port "$NAME-app" 1883/tcp | awk -F: '{print $NF}')
python3 - "$MQTT_PORT" <<'PY'
import socket,sys
with socket.create_connection(('127.0.0.1',int(sys.argv[1])),5) as conn:
    conn.settimeout(5)
    conn.sendall(b'\x10\x17\x00\x04MQTT\x04\x02\x00\x0a\x00\x0bzg-probe-v1')
    reply=b''
    while len(reply)<4:
        chunk=conn.recv(4-len(reply))
        if not chunk: break
        reply+=chunk
    if len(reply)!=4 or reply[:2]!=b'\x20\x02' or reply[3]>5:
        sys.exit('Image MQTT protocol smoke test failed')
PY
echo "Image smoke test passed: $IMAGE"
