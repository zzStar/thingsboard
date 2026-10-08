#!/usr/bin/env bash
#
# SPDX-FileCopyrightText: Copyright ThingsBoard, Inc.
# SPDX-License-Identifier: BUSL-1.1
#

set -euo pipefail
IMAGE=${1:?image required}
for attempt in 1 2 3; do
  echo "推送镜像（第 ${attempt}/3 次）：${IMAGE}"
  if docker push "$IMAGE"; then exit 0; fi
  if (( attempt < 3 )); then
    echo '推送失败，5 秒后重试；已上传的完整镜像层会复用。' >&2
    sleep 5
  fi
done
echo "推送仍失败，请检查 Docker Desktop 代理或网络。无需重新构建，可执行：make deploy VERSION=${IMAGE##*:} BUILD=0 RESUME_PUSH=1" >&2
exit 1
