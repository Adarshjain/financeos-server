#!/usr/bin/env bash
# Deploys target/backend-1.0.0.jar to one host (used by .github/workflows/deploy.yml, API and worker alike):
# upload as .new, pause + drain background jobs, swap the JAR and restart, wait for health on localhost:8081.
# On any failure it exits non-zero BEFORE later hosts are touched. Resuming a paused job worker after a
# failure is the workflow's job (deploy/resume-jobs.sh).
#
#   deploy/deploy-host.sh <host> <user> <label>
# Env: APP_DIR, MGMT_PORT, DRAIN_TIMEOUT_SECONDS; SSH key at ~/.ssh/deploy_key.
set -euo pipefail

HOST="${1:?host}"
USER_NAME="${2:?user}"
LABEL="${3:-$HOST}"
APP_DIR="${APP_DIR:-/home/ubuntu/financeos-server}"
MGMT_PORT="${MGMT_PORT:-8081}"
DRAIN_TIMEOUT_SECONDS="${DRAIN_TIMEOUT_SECONDS:-2400}"

SSH=(ssh -i "$HOME/.ssh/deploy_key" -o StrictHostKeyChecking=accept-new "$USER_NAME@$HOST")

echo "::group::[$LABEL] Upload JAR"
scp -i "$HOME/.ssh/deploy_key" -o StrictHostKeyChecking=accept-new \
  target/backend-1.0.0.jar "$USER_NAME@$HOST:$APP_DIR/target/backend-1.0.0.jar.new"
echo "::endgroup::"

# Pause the job worker (no new PENDING jobs are claimed; they stay queued for the next boot)
# and wait until nothing is running, so a restart never interrupts a job mid-flight.
echo "[$LABEL] Draining background jobs"
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

echo "[$LABEL] Swapping in new JAR and restarting"
"${SSH[@]}" bash -se <<EOF
set -euo pipefail
cd $APP_DIR/target
# Keep the outgoing JAR so a bad deploy can be rolled back by hand.
[ -f backend-1.0.0.jar ] && cp -f backend-1.0.0.jar backend-1.0.0.jar.prev
mv -f backend-1.0.0.jar.new backend-1.0.0.jar
sudo systemctl restart financeos
EOF

echo "[$LABEL] Waiting for health"
for i in $(seq 1 45); do
  if "${SSH[@]}" "curl -sf http://localhost:$MGMT_PORT/actuator/health" >/dev/null 2>&1; then
    echo "[$LABEL] Healthy after $((i * 4))s."
    exit 0
  fi
  sleep 4
done
echo "::error::[$LABEL] Service did not become healthy. Rolling back is manual: see deploy/README.md"
"${SSH[@]}" 'sudo journalctl -u financeos -n 80 --no-pager' || true
exit 1
