#!/usr/bin/env bash
#
# SPDX-FileCopyrightText: Copyright ThingsBoard, Inc.
# SPDX-License-Identifier: BUSL-1.1
#

# A disconnected SSH session must not interrupt an application replacement.
set -euo pipefail
BASE=${TB_DEPLOY_BASE:-/opt/zhigong-things}
ACTION=${1:?action required}
umask 077
case "$ACTION" in
  start)
    TASK=${2:?task required} VERSION=${3:-} FROM_VERSION=${4:-} MODE=${5:-single}
    [[ "$TASK" == deploy || "$TASK" == rollback || "$TASK" == backup ]] || exit 2
    [[ "$TASK" == backup || "$VERSION" =~ ^[a-zA-Z0-9][a-zA-Z0-9_.-]{0,100}$ ]] || exit 2
    [[ -z "$FROM_VERSION" || "$FROM_VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+(\.[0-9]+)?$ ]] || exit 2
    [[ "$MODE" == single || "$MODE" == maintenance ]] || exit 2
    mkdir -p "$BASE/jobs"
    exec 8>"$BASE/job.lock"
    flock -n 8 || { echo 'Another launch is running' >&2; exit 1; }
    if [[ -f "$BASE/jobs/active" ]]; then
      read -r OLD_JOB < "$BASE/jobs/active"
      [[ "$OLD_JOB" =~ ^[a-zA-Z0-9_.-]+$ ]] || exit 2
      if [[ ! -f "$BASE/jobs/$OLD_JOB/result" ]] && [[ -f "$BASE/jobs/$OLD_JOB/pid" ]]; then
        read -r OLD_PID < "$BASE/jobs/$OLD_JOB/pid"
        if kill -0 "$OLD_PID" 2>/dev/null; then
          echo "A deployment job is still running: $OLD_JOB. Use make deploy-status." >&2
          exit 1
        fi
      fi
    fi
    JOB=$(date -u +%Y%m%dT%H%M%SZ)-$$
    DIR="$BASE/jobs/$JOB"
    mkdir "$DIR"
    cp "$BASE/deploy-remote.sh" "$BASE/deploy-policy.py" "$DIR/"
    cat > "$DIR/run.sh" <<'RUN'
#!/usr/bin/env bash
set +e
DIR=$(cd "$(dirname "$0")" && pwd)
export TB_DEPLOY_RUN_ID="${DIR##*/}"
bash "$DIR/deploy-remote.sh" "$@"
result=$?
printf '%s\n' "$result" > "$DIR/result.tmp"
mv "$DIR/result.tmp" "$DIR/result"
exit "$result"
RUN
    # The child must not inherit the launcher lock, otherwise the next launch stays blocked.
    nohup bash "$DIR/run.sh" "$TASK" "$VERSION" "$FROM_VERSION" "$MODE" 8>&- </dev/null > "$DIR/output.log" 2>&1 &
    printf '%s\n' "$!" > "$DIR/pid"
    printf '%s\n' "$JOB" > "$BASE/jobs/active.tmp"
    mv "$BASE/jobs/active.tmp" "$BASE/jobs/active"
    echo "$JOB"
    ;;
  poll)
    JOB=${2:?job required}
    [[ "$JOB" =~ ^[a-zA-Z0-9_.-]+$ ]] || exit 2
    DIR="$BASE/jobs/$JOB"
    [[ -d "$DIR" ]] || exit 1
    if [[ -f "$DIR/result" ]]; then
      printf 'DONE '
      cat "$DIR/result"
    else
      read -r PID < "$DIR/pid"
      if kill -0 "$PID" 2>/dev/null; then echo RUNNING; else echo 'DONE 1'; fi
    fi
    tail -n 12 "$DIR/output.log"
    ;;
  *) exit 2 ;;
esac
