#!/usr/bin/env bash
#
# SPDX-FileCopyrightText: Copyright ThingsBoard, Inc.
# SPDX-License-Identifier: BUSL-1.1
#

set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$ROOT"
HOST=${DEPLOY_HOST:-root@47.100.121.136}
IP=47.100.121.136
DOMAIN=things.zhigongshulian.com
REGISTRY=registry.cn-hangzhou.aliyuncs.com
REPO=$REGISTRY/zgsl/things
ACTION=${1:-deploy}
BUILD=${BUILD:-1}
VERSION=${VERSION:-}
FROM_VERSION=${FROM_VERSION:-}
SSH=(ssh -o BatchMode=yes -o ConnectTimeout=10 "$HOST")
fail() { echo "ERROR: $*" >&2; exit 1; }
if [[ "$ACTION" == status || "$ACTION" == logs ]]; then
  "${SSH[@]}" "bash /opt/zhigong-things/deploy-remote.sh $ACTION"
  exit
fi
[[ "$ACTION" == deploy || "$ACTION" == rollback || "$ACTION" == backup ]] || fail 'Unknown action'
mkdir -p .dev
if [[ "$ACTION" == deploy && -z "$VERSION" ]]; then
  VERSION=$(TZ=Asia/Shanghai date +%Y%m%d-%H%M%S)-$(git rev-parse --short HEAD)
fi
if [[ "$ACTION" != backup ]]; then
  [[ "$VERSION" =~ ^[a-zA-Z0-9][a-zA-Z0-9_.-]{0,100}$ ]] || fail 'Supply a valid VERSION'
fi
[[ -z "$FROM_VERSION" || "$FROM_VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+(\.[0-9]+)?$ ]] || fail 'Invalid FROM_VERSION'
if [[ "$ACTION" == deploy ]]; then
  for cmd in docker python3 ssh tar; do command -v "$cmd" >/dev/null || fail "Missing command $cmd"; done
  python3 - "$DOMAIN" "$IP" <<'PY'
import socket,sys
addresses={r[4][0] for r in socket.getaddrinfo(sys.argv[1],80,proto=socket.IPPROTO_TCP)}
if addresses!={sys.argv[2]}:
    sys.exit('域名 '+sys.argv[1]+' 必须仅解析到 '+sys.argv[2]+
             '；当前解析：'+', '.join(sorted(addresses))+
             '\n请在域名 DNS 控制台删除或修正其他 A/AAAA 记录，等待缓存过期后重新执行 make deploy。')
print('DNS verified')
PY
  "${SSH[@]}" 'test "$(uname -m)" = x86_64 && docker info >/dev/null && docker compose version >/dev/null && command -v flock >/dev/null && command -v curl >/dev/null' || fail 'Server prerequisites failed'
  docker info >/dev/null
  docker buildx version >/dev/null
  if [[ "$BUILD" == 1 ]]; then
    DOCKER_MEMORY=$(docker info --format '{{.MemTotal}}')
    (( DOCKER_MEMORY >= 7 * 1024 * 1024 * 1024 )) || fail 'Docker 构建内存不足：请在 Docker Desktop Settings → Resources → Memory 分配至少 8 GiB，建议 10–12 GiB。'
    echo '生产构建：Node 堆上限 4 GiB，Angular workers=2，Maven 堆上限 1 GiB。建议 Docker 分配 10–12 GiB 内存，并停止其他高内存容器。'
  fi
  python3 scripts/registry-login.py "$REGISTRY" local
  python3 scripts/registry-login.py "$REGISTRY" "$HOST"
  if [[ "$BUILD" == 1 ]]; then
    if docker buildx imagetools inspect "$REPO:$VERSION" > /dev/null 2>&1; then
      fail 'Version already exists; use a new VERSION, or BUILD=0 to redeploy it.'
    fi
    SCHEMA=$(python3 - <<'PY'
import xml.etree.ElementTree as E,re
r=E.parse('pom.xml').getroot();v=r.findtext('{http://maven.apache.org/POM/4.0.0}version')
a=list(map(int,re.match(r'^(\d+)\.(\d+)\.(\d+)(?:\.(\d+))?',v).groups(default='0')))
print(a[0]*1000000000+a[1]*1000000+a[2]*1000+a[3])
PY
)
    SOURCE=$(git rev-parse HEAD)
    [[ -z "$(git status --porcelain)" ]] || SOURCE="$SOURCE-dirty"
    echo "Building $REPO:$VERSION (linux/amd64)"
    docker buildx build --platform linux/amd64 -f deploy/Dockerfile \
      --build-arg "APP_SCHEMA=$SCHEMA" --build-arg "APP_SOURCE=$SOURCE" \
      --tag "$REPO:$VERSION" --load --progress plain . \
      2>&1 | tee ".dev/deploy-build-$VERSION.log"
    bash scripts/smoke-image.sh "$REPO:$VERSION" "$SCHEMA"
    docker push "$REPO:$VERSION"
  elif [[ "$BUILD" != 0 ]]; then fail 'BUILD must be 0 or 1'; fi
  docker buildx imagetools inspect "$REPO:$VERSION" >/dev/null
  # Stage configuration in an immutable version directory; preserve existing release files on retries.
  "${SSH[@]}" "mkdir -p /opt/zhigong-things/releases/$VERSION"
  tar -cf - deploy/compose.yml deploy/Caddyfile scripts/deploy-remote.sh | \
    "${SSH[@]}" "tar -xf - -C /opt/zhigong-things/releases/$VERSION"
  "${SSH[@]}" "cd /opt/zhigong-things/releases/$VERSION && test -f compose.yml || { cp deploy/compose.yml compose.yml; cp deploy/Caddyfile Caddyfile; }"
  "${SSH[@]}" "cp /opt/zhigong-things/releases/$VERSION/scripts/deploy-remote.sh /opt/zhigong-things/deploy-remote.sh"
fi
"${SSH[@]}" "bash -c \"\$(cat /opt/zhigong-things/deploy-remote.sh)\" /opt/zhigong-things/deploy-remote.sh $ACTION ${VERSION:-''} ${FROM_VERSION:-''}"
