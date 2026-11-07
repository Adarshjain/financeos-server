#!/usr/bin/env bash
# Un-pauses the job worker on one host after a failed or cancelled deploy (a restarted JVM starts unpaused,
# so this is a no-op once the restart happened). Best effort: never fails.
#   deploy/resume-jobs.sh <host> <user>
set -uo pipefail
HOST="${1:?host}"; USER_NAME="${2:?user}"; MGMT_PORT="${MGMT_PORT:-8081}"
ssh -i "$HOME/.ssh/deploy_key" -o StrictHostKeyChecking=accept-new "$USER_NAME@$HOST" \
  "curl -s -m 10 -X POST -H 'Content-Type: application/json' -d '{\"paused\":false}' http://127.0.0.1:$MGMT_PORT/actuator/jobs" || true
