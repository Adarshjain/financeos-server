#!/bin/bash
# shellcheck disable=SC2029  # remote commands are built locally on purpose
# One-time switch from one VM (APP_ROLE unset = all) to API + worker, run from the owner's Mac.
#
# Why not just let CI deploy worker-first the first time: until VM1 restarts it runs the OLD role=all
# process, so both VMs would run schedulers, and the worker's startup sweep would mark VM1's in-flight
# jobs as interrupted. This script drains VM1 first and restarts it straight after the worker is up.
#
# Preconditions (see README "Two-VM layout"):
#   1. VM1 already runs the worker-split JAR (CI deploy with WORKER_HOST unset).
#   2. deploy/copy-worker-secrets.sh has run (VM1 .env APP_ROLE=api, VM2 .env APP_ROLE=worker).
#
# Usage: deploy/first-cutover.sh
set -euo pipefail

KEY="${KEY:-$HOME/.ssh/oracle-oci}"
API="${API_HOST:-ubuntu@129.159.22.124}"
WORKER="${WORKER_HOST:-ubuntu@152.70.68.140}"
APP_DIR=/home/ubuntu/financeos-server
JOBS=http://127.0.0.1:8081/actuator/jobs
SSH_OPTS=(-i "$KEY" -o ConnectTimeout=15)

on_api()    { ssh "${SSH_OPTS[@]}" "$API" "$@"; }
on_worker() { ssh "${SSH_OPTS[@]}" "$WORKER" "$@"; }
resume_api_jobs() {
  on_api "curl -s -m 10 -o /dev/null -X POST -H 'Content-Type: application/json' -d '{\"paused\":false}' $JOBS" || true
}
wait_healthy() {  # $1 = on_api|on_worker, $2 = label
  for i in $(seq 1 75); do
    if "$1" "curl -sf http://localhost:8081/actuator/health" >/dev/null 2>&1; then
      echo "$2 healthy after $((i * 4))s"; return 0
    fi
    sleep 4
  done
  echo "$2 did not become healthy in 300s"; return 1
}

echo "== 0. Preconditions"
on_api    "grep -qx 'APP_ROLE=api' $APP_DIR/.env"    || { echo "VM1 .env lacks APP_ROLE=api; run copy-worker-secrets.sh"; exit 1; }
on_worker "grep -qx 'APP_ROLE=worker' $APP_DIR/.env" || { echo "VM2 .env lacks APP_ROLE=worker; run copy-worker-secrets.sh"; exit 1; }
on_worker "test -s $APP_DIR/wallet/cwallet.sso"      || { echo "VM2 wallet missing; run copy-worker-secrets.sh"; exit 1; }
on_api "unzip -l $APP_DIR/target/backend-1.0.0.jar | grep -q 'com/financeos/core/role/AppRole.class'" \
  || { echo "VM1 JAR predates the split; deploy it first (CI with WORKER_HOST unset)"; exit 1; }

echo "== 1. Copy VM1's JAR to VM2 (streamed, nothing written locally)"
on_api "cat $APP_DIR/target/backend-1.0.0.jar" | on_worker "cat > $APP_DIR/target/backend-1.0.0.jar.new && mv -f $APP_DIR/target/backend-1.0.0.jar.new $APP_DIR/target/backend-1.0.0.jar"
a=$(on_api "sha256sum $APP_DIR/target/backend-1.0.0.jar" | cut -d' ' -f1)
w=$(on_worker "sha256sum $APP_DIR/target/backend-1.0.0.jar" | cut -d' ' -f1)
[ "$a" = "$w" ] || { echo "JAR checksum mismatch"; exit 1; }

echo "== 2. Pause VM1's job worker and wait for running jobs to finish"
on_api "curl -sf -m 10 -o /dev/null -X POST -H 'Content-Type: application/json' -d '{\"paused\":true}' $JOBS"
for i in $(seq 1 480); do
  running=$(on_api "curl -s -m 10 $JOBS" | grep -o '"running": *[0-9]*' | grep -o '[0-9]*$' || true)
  [ "$running" = "0" ] && { echo "VM1 idle"; break; }
  [ "$i" = 480 ] && { echo "jobs still running after 40 min; resuming VM1, aborting"; resume_api_jobs; exit 1; }
  echo "waiting on ${running:-?} running job(s)"; sleep 5
done

echo "== 3. Start the worker on VM2"
on_worker "sudo systemctl restart financeos"
if ! wait_healthy on_worker "VM2 worker"; then
  on_worker "sudo systemctl stop financeos; sudo journalctl -u financeos -n 60 --no-pager" || true
  resume_api_jobs
  echo "Worker failed; VM2 stopped, VM1 resumed as before."; exit 1
fi

echo "== 4. Restart VM1 as API (APP_ROLE=api)"
on_api "sudo systemctl restart financeos"
wait_healthy on_api "VM1 API" || { on_api "sudo journalctl -u financeos -n 60 --no-pager"; exit 1; }

echo "== 5. Roles as reported by each node"
on_api    "curl -s http://127.0.0.1:8081/actuator/prometheus | grep -m1 -o 'role=\"[a-z]*\"'"
on_worker "curl -s http://127.0.0.1:8081/actuator/prometheus | grep -m1 -o 'role=\"[a-z]*\"'"
echo "Done. Next: set the GitHub secret WORKER_HOST so future deploys go worker-first, then push the Grafana alerts."
