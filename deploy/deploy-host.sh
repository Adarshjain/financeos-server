#!/usr/bin/env bash
# Deploys target/backend-1.0.0.jar to one host (used by .github/workflows/deploy.yml, API and worker alike):
# upload as .new, pause + drain background jobs, swap the JAR and restart, wait for health on localhost:8081.
# A new JAR that never gets healthy is rolled back to the previous one automatically (backend-1.0.0.jar.prev),
# so a failed deploy leaves the host serving the old build, not down.
#
#   deploy/deploy-host.sh <host> <user> <label>            deploy the new JAR
#   deploy/deploy-host.sh <host> <user> <label> rollback   put the previous JAR back (the workflow uses this on the
#                                                          worker when the API deploy after it failed)
#
# Exit: 0 deployed (or rolled back, in rollback mode); 1 aborted before the swap (old JAR still serving);
#       2 new JAR unhealthy, rolled back, old JAR healthy; 3 host not healthy on either JAR.
# Writes result=<deployed|aborted|rolled_back|down> and version=<short sha running now> to $GITHUB_OUTPUT when set.
# Resuming a paused job worker after an abort is the workflow's job (deploy/resume-jobs.sh).
# Env: APP_DIR, MGMT_PORT, DRAIN_TIMEOUT_SECONDS, HEALTH_TRIES (x4 s); SSH key at ~/.ssh/deploy_key.
set -uo pipefail

HOST="${1:?host}"
USER_NAME="${2:?user}"
LABEL="${3:-$HOST}"
MODE="${4:-deploy}"
APP_DIR="${APP_DIR:-/home/ubuntu/financeos-server}"
MGMT_PORT="${MGMT_PORT:-8081}"
DRAIN_TIMEOUT_SECONDS="${DRAIN_TIMEOUT_SECONDS:-2400}"
HEALTH_TRIES="${HEALTH_TRIES:-45}"

SSH=(ssh -i "$HOME/.ssh/deploy_key" -o StrictHostKeyChecking=accept-new "$USER_NAME@$HOST")

# Short git sha of the build the host is serving now (from the metrics version tag), or "unknown".
running_version() {
  local v
  v=$("${SSH[@]}" "curl -s -m 10 http://127.0.0.1:$MGMT_PORT/actuator/prometheus | grep -m1 '^jvm_memory_used_bytes{' | grep -o 'version=\"[^\"]*\"'" 2>/dev/null \
      | sed -E 's/^version="(.*)"$/\1/; s/^.*-([0-9a-f]{7})[0-9a-f]*$/\1/')
  echo "${v:-unknown}"
}

finish() {   # $1 result, $2 exit code
  local version
  version=$(running_version)
  echo "[$LABEL] result=$1 version=$version"
  if [ -n "${GITHUB_OUTPUT:-}" ]; then
    echo "result=$1" >> "$GITHUB_OUTPUT"
    echo "version=$version" >> "$GITHUB_OUTPUT"
  fi
  exit "$2"
}

wait_healthy() {
  local i
  for i in $(seq 1 "$HEALTH_TRIES"); do
    if "${SSH[@]}" "curl -sf http://127.0.0.1:$MGMT_PORT/actuator/health" >/dev/null 2>&1; then
      echo "[$LABEL] Healthy after $((i * 4))s."
      return 0
    fi
    sleep 4
  done
  return 1
}

# Puts backend-1.0.0.jar.prev back as the live JAR (the replaced one is kept as .bad) and restarts.
restore_previous() {
  "${SSH[@]}" bash -se <<EOF
set -euo pipefail
cd $APP_DIR/target
if [ ! -f backend-1.0.0.jar.prev ]; then echo "::error::No backend-1.0.0.jar.prev to roll back to."; exit 1; fi
cp -f backend-1.0.0.jar backend-1.0.0.jar.bad
cp -f backend-1.0.0.jar.prev backend-1.0.0.jar
sudo systemctl restart financeos
EOF
}

# Pause the job worker (no new PENDING jobs are claimed; they stay queued for the next boot)
# and wait until nothing is running, so a restart never interrupts a job mid-flight.
echo "[$LABEL] Draining background jobs"
drain() {
"${SSH[@]}" bash -s -- "$MGMT_PORT" "$DRAIN_TIMEOUT_SECONDS" <<'EOF'
set -uo pipefail
base="http://127.0.0.1:$1/actuator"
url="$base/jobs"
timeout="$2"
# Ask the actuator index which endpoints the running build exposes: builds older than the
# jobs endpoint answer an unknown actuator path with a 500, not a 404, so probing it can't
# tell "no endpoint" from "pause failed".
index=$(mktemp)
code=$(curl -s -m 10 -o "$index" -w '%{http_code}' "$base")
case "$code" in
  200) ;;
  000) echo "Server not reachable; nothing to drain."; exit 0 ;;
  *)   echo "::error::Reading the actuator index failed (HTTP $code)."; exit 1 ;;
esac
if ! grep -q '"jobs"' "$index"; then
  echo "::warning::Running build does not expose /actuator/jobs; restarting without a drain."
  exit 0
fi
code=$(curl -s -m 10 -o /dev/null -w '%{http_code}' -X POST \
         -H 'Content-Type: application/json' -d '{"paused":true}' "$url")
if [ "$code" != "200" ]; then
  echo "::error::Pausing the job worker failed (HTTP $code)."
  exit 1
fi
elapsed=0
while :; do
  running=$(curl -s -m 10 "$url" | grep -o '"running": *[0-9]*' | grep -o '[0-9]*$')
  if [ "$running" = "0" ]; then
    echo "No running jobs after ${elapsed}s; safe to restart."
    exit 0
  fi
  if [ "$elapsed" -ge "$timeout" ]; then
    curl -s -m 10 -o /dev/null -X POST -H 'Content-Type: application/json' \
      -d '{"paused":false}' "$url" || true
    echo "::error::${running:-?} job(s) still running after ${timeout}s; worker resumed, deploy aborted. Re-run once they finish."
    exit 1
  fi
  echo "Waiting on ${running:-?} running job(s)... (${elapsed}s)"
  sleep 5
  elapsed=$((elapsed + 5))
done
EOF
}

if [ "$MODE" = "rollback" ]; then
  if ! "${SSH[@]}" "test -f $APP_DIR/target/backend-1.0.0.jar.prev"; then
    echo "::error::[$LABEL] No backend-1.0.0.jar.prev to roll back to; host left as it is."
    if wait_healthy; then finish aborted 1; fi
    finish down 3
  fi
  echo "[$LABEL] Rolling back to the previous JAR"
  drain || echo "::warning::[$LABEL] Drain failed; rolling back anyway so both VMs end on one build."
  if restore_previous && wait_healthy; then finish rolled_back 0; fi
  echo "::error::[$LABEL] Not healthy after the rollback either."
  "${SSH[@]}" 'sudo journalctl -u financeos -n 80 --no-pager' || true
  finish down 3
fi

echo "::group::[$LABEL] Upload JAR"
if ! scp -i "$HOME/.ssh/deploy_key" -o StrictHostKeyChecking=accept-new \
     target/backend-1.0.0.jar "$USER_NAME@$HOST:$APP_DIR/target/backend-1.0.0.jar.new"; then
  echo "::error::[$LABEL] Upload failed; nothing changed on the host."
  finish aborted 1
fi
echo "::endgroup::"

if ! drain; then
  echo "::error::[$LABEL] Drain failed; the old JAR keeps running."
  finish aborted 1
fi

echo "[$LABEL] Swapping in new JAR and restarting"
"${SSH[@]}" bash -se <<EOF
set -euo pipefail
cd $APP_DIR/target
# Keep the outgoing JAR (written via a temp file so a full disk never leaves a truncated .prev): the automatic
# rollback below and a later rollback-mode run restore it. Exit 10 = failed before the live JAR was replaced.
if [ -f backend-1.0.0.jar ]; then
  cp -f backend-1.0.0.jar backend-1.0.0.jar.prev.tmp && mv -f backend-1.0.0.jar.prev.tmp backend-1.0.0.jar.prev || exit 10
fi
[ -f backend-1.0.0.jar.new ] || exit 10
mv -f backend-1.0.0.jar.new backend-1.0.0.jar
sudo systemctl restart financeos
EOF
swap=$?
if [ "$swap" -eq 10 ]; then
  echo "::error::[$LABEL] Could not keep the previous JAR or find the new one; the old JAR keeps running (the workflow resumes its paused jobs)."
  finish aborted 1
fi

echo "[$LABEL] Waiting for health"
if [ "$swap" -eq 0 ] && wait_healthy; then finish deployed 0; fi

echo "::error::[$LABEL] New JAR not healthy; rolling back to the previous JAR."
"${SSH[@]}" 'sudo journalctl -u financeos -n 80 --no-pager' || true
if restore_previous && wait_healthy; then
  echo "::error::[$LABEL] Rolled back: the previous build is serving again."
  finish rolled_back 2
fi
echo "::error::[$LABEL] Not healthy after the rollback either."
"${SSH[@]}" 'sudo journalctl -u financeos -n 80 --no-pager' || true
finish down 3
