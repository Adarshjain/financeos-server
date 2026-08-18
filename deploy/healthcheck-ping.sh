#!/usr/bin/env bash
# Dead man's switch heartbeat. Runs from cron on the VM every 5 minutes.
#
# Pings HC_URL only when the app reports UP. On a bad check it pings HC_URL/fail so a
# sick-but-running app alerts immediately instead of waiting out the grace period.
# Silence — box off, JVM OOM-killed, network gone, cron dead — trips the grace period
# at the other end, which is the failure mode nothing on this box could report itself.
set -uo pipefail

HC_URL="${HC_URL:?set HC_URL to the healthchecks.io ping URL}"
MGMT_URL="${MGMT_URL:-http://localhost:8081/actuator/health}"

# -f matters: Spring returns 503 when any component is DOWN, so a dead Oracle
# connection fails here rather than passing as a 200.
if body="$(curl -fsS --max-time 10 "$MGMT_URL" 2>&1)"; then
  curl -fsS -m 10 --retry 3 "$HC_URL" >/dev/null
else
  # Body is component status only (db/diskSpace/ping) — no user or financial data.
  curl -fsS -m 10 --retry 3 --data-raw "$body" "$HC_URL/fail" >/dev/null
fi
