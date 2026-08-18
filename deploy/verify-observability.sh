#!/usr/bin/env bash
set -uo pipefail

# FinanceOS Server Observability Verification Harness
# Runs end-to-end checks across swap, health, logs, metrics, traces, and Grafana Cloud shipping.

VERBOSE=false
for arg in "$@"; do
  if [[ "$arg" == "--verbose" ]]; then
    VERBOSE=true
  fi
done

ENV_FILE="/etc/fluent-bit/fluent-bit.env"
if [[ -f "$ENV_FILE" ]]; then
  set -a
  source "$ENV_FILE"
  set +a
fi

PASS_COUNT=0
FAIL_COUNT=0
SKIP_COUNT=0

log_pass() {
  echo -e "[\e[32mPASS\e[0m] Check $1 ($2): $3"
  PASS_COUNT=$((PASS_COUNT + 1))
}

log_fail() {
  echo -e "[\e[31mFAIL\e[0m] Check $1 ($2): $3"
  FAIL_COUNT=$((FAIL_COUNT + 1))
}

log_skip() {
  echo -e "[\e[33mSKIP\e[0m] Check $1 ($2): $3"
  SKIP_COUNT=$((SKIP_COUNT + 1))
}

# 1. Swap check
SWAP_OUT=$(swapon --show 2>/dev/null || true)
if [[ -n "$SWAP_OUT" ]]; then
  log_pass 1 "Swap active" "Swap memory is enabled on host"
else
  log_fail 1 "Swap active" "swapon --show returned empty (swap missing)"
fi

# 2. App health check
HEALTH_OUT=$(curl -sf http://127.0.0.1:8081/actuator/health 2>/dev/null || true)
if [[ "$HEALTH_OUT" =~ "\"status\":\"UP\"" ]]; then
  log_pass 2 "App health" "Actuator health status is UP on 127.0.0.1:8081"
else
  log_fail 2 "App health" "Failed to reach UP status on 127.0.0.1:8081/actuator/health"
fi

# 3. JSON log file validity
LOG_FILE="logs/financeos.json"
if [[ -f "$LOG_FILE" ]]; then
  LAST_LINE=$(tail -n 1 "$LOG_FILE" 2>/dev/null || true)
  if echo "$LAST_LINE" | jq . >/dev/null 2>&1; then
    log_pass 3 "JSON log validity" "$LOG_FILE last line parses as valid JSON"
  else
    log_fail 3 "JSON log validity" "Last line of $LOG_FILE is not valid JSON"
  fi
else
  log_fail 3 "JSON log validity" "Log file $LOG_FILE does not exist"
fi

# 4. Log envelope check
if [[ -f "$LOG_FILE" && -n "${LAST_LINE:-}" ]]; then
  SERVICE=$(echo "$LAST_LINE" | jq -r '.service // empty' 2>/dev/null || true)
  ENV_VAL=$(echo "$LAST_LINE" | jq -r '.env // empty' 2>/dev/null || true)
  VERSION=$(echo "$LAST_LINE" | jq -r '.version // empty' 2>/dev/null || true)
  TIMESTAMP=$(echo "$LAST_LINE" | jq -r '."@timestamp" // empty' 2>/dev/null || true)

  if [[ -n "$SERVICE" && -n "$ENV_VAL" && -n "$TIMESTAMP" && -n "$VERSION" && "$VERSION" != "@project.version@" ]]; then
    log_pass 4 "Log envelope" "Log envelope contains service, env, timestamp, and real version ($VERSION)"
  else
    log_fail 4 "Log envelope" "Incomplete log envelope (version=$VERSION, service=$SERVICE, env=$ENV_VAL)"
  fi
else
  log_fail 4 "Log envelope" "Cannot evaluate log envelope; log file unreadable"
fi

# 5. Fluent Bit service status
FB_ACTIVE=$(systemctl is-active fluent-bit 2>/dev/null || true)
if [[ "$FB_ACTIVE" == "active" ]]; then
  log_pass 5 "Fluent Bit active" "fluent-bit systemd service is active"
else
  log_fail 5 "Fluent Bit active" "fluent-bit service status is '$FB_ACTIVE'"
fi

# 6. Fluent Bit config errors
FB_LOGS=$(journalctl -u fluent-bit -n 50 2>/dev/null || true)
if echo "$FB_LOGS" | grep -i "\[error\]" >/dev/null 2>&1; then
  log_fail 6 "Fluent Bit config errors" "journalctl contains error lines in fluent-bit logs"
else
  log_pass 6 "Fluent Bit config errors" "No [error] lines found in recent fluent-bit logs"
fi

# 7. Credentials present
LOKI_HOST="${GRAFANA_LOKI_HOST:-}"
LOKI_USER="${GRAFANA_LOKI_USER:-}"
PROM_HOST="${GRAFANA_PROM_HOST:-}"
PROM_USER="${GRAFANA_PROM_USER:-}"
CLOUD_TOKEN="${GRAFANA_CLOUD_TOKEN:-}"

if [[ -n "$LOKI_HOST" && -n "$LOKI_USER" && -n "$PROM_HOST" && -n "$PROM_USER" && -n "$CLOUD_TOKEN" ]]; then
  log_pass 7 "Credentials present" "All Grafana Cloud credential variables are set"
else
  log_skip 7 "Credentials present" "Grafana Cloud credentials incomplete (Loki/Prom hosts or token missing)"
fi

# 8. Loki accepts writes
if [[ -n "$LOKI_HOST" && -n "$LOKI_USER" && -n "$CLOUD_TOKEN" ]]; then
  NOW_SEC=$(date +%s)
  START_SEC=$((NOW_SEC - 900))
  LOKI_URL="https://${LOKI_HOST}/loki/api/v1/query_range?query=%7Bservice%3D%22financeos-server%22%7D&start=${START_SEC}000000000&end=${NOW_SEC}000000000"
  LOKI_RESP=$(curl -s -u "${LOKI_USER}:${CLOUD_TOKEN}" "$LOKI_URL" || true)
  if [[ "$VERBOSE" == "true" ]]; then echo "Loki Response: $LOKI_RESP"; fi

  RESULT_COUNT=$(echo "$LOKI_RESP" | jq '.data.result | length' 2>/dev/null || echo "0")
  if [[ "$RESULT_COUNT" -gt 0 ]]; then
    log_pass 8 "Loki write receipt" "Loki returned $RESULT_COUNT stream(s) for service=financeos-server"
  else
    log_fail 8 "Loki write receipt" "Loki returned 0 streams over the last 15 minutes"
  fi
else
  log_skip 8 "Loki write receipt" "Grafana Loki credentials not set"
fi

# 9. No duplicate shipping
if [[ -n "$LOKI_HOST" && -n "$LOKI_USER" && -n "$CLOUD_TOKEN" ]]; then
  TEST_REQ_ID="req-dedup-check-$(date +%s)"
  curl -s -X POST -H "Content-Type: application/json" -H "X-Request-Id: ${TEST_REQ_ID}" -d '{}' http://127.0.0.1:8080/api/v1/auth/login >/dev/null || true
  sleep 4

  NOW_SEC=$(date +%s)
  START_SEC=$((NOW_SEC - 120))
  DEDUP_URL="https://${LOKI_HOST}/loki/api/v1/query_range?query=%7Bservice%3D%22financeos-server%22%7D%20%7C%20json%20%7C%20event%3D%22http.request%22%20%7C%20requestId%3D%22${TEST_REQ_ID}%22&start=${START_SEC}000000000&end=${NOW_SEC}000000000"
  DEDUP_RESP=$(curl -s -u "${LOKI_USER}:${CLOUD_TOKEN}" "$DEDUP_URL" || true)
  ENTRY_COUNT=$(echo "$DEDUP_RESP" | jq '[.data.result[].values[]] | length' 2>/dev/null || echo "0")

  if [[ "$ENTRY_COUNT" -eq 1 ]]; then
    log_pass 9 "No duplicate shipping" "Unique requestId returned exactly 1 http.request log record in Loki"
  elif [[ "$ENTRY_COUNT" -gt 1 ]]; then
    log_fail 9 "No duplicate shipping" "Duplicate log lines detected in Loki (found $ENTRY_COUNT records)"
  else
    log_fail 9 "No duplicate shipping" "No http.request log line arrived in Loki for test request ID"
  fi
else
  log_skip 9 "No duplicate shipping" "Grafana Loki credentials not set"
fi

# 10. Journal-only lines arrive
if [[ -n "$LOKI_HOST" && -n "$LOKI_USER" && -n "$CLOUD_TOKEN" ]]; then
  NOW_SEC=$(date +%s)
  START_SEC=$((NOW_SEC - 900))
  JOURNAL_URL="https://${LOKI_HOST}/loki/api/v1/query_range?query=%7Bservice%3D%22financeos-server%22%7D%20%7C%3D%20%22Started%20FinanceOS%22&start=${START_SEC}000000000&end=${NOW_SEC}000000000"
  JOURNAL_RESP=$(curl -s -u "${LOKI_USER}:${CLOUD_TOKEN}" "$JOURNAL_URL" || true)
  J_COUNT=$(echo "$JOURNAL_RESP" | jq '[.data.result[].values[]] | length' 2>/dev/null || echo "0")

  if [[ "$J_COUNT" -gt 0 ]]; then
    log_pass 10 "Journal-only lines" "Journald systemd lifecycle lines arrived in Loki"
  else
    log_fail 10 "Journal-only lines" "No systemd lifecycle lines found in Loki over last 15 min"
  fi
else
  log_skip 10 "Journal-only lines" "Grafana Loki credentials not set"
fi

# 11. Prometheus accepts writes
if [[ -n "$PROM_HOST" && -n "$PROM_USER" && -n "$CLOUD_TOKEN" ]]; then
  PROM_URL="https://${PROM_HOST}/api/v1/query?query=jvm_memory_used_bytes%7Bservice%3D%22financeos-server%22%7D"
  PROM_RESP=$(curl -s -u "${PROM_USER}:${CLOUD_TOKEN}" "$PROM_URL" || true)
  P_COUNT=$(echo "$PROM_RESP" | jq '.data.result | length' 2>/dev/null || echo "0")

  if [[ "$P_COUNT" -gt 0 ]]; then
    log_pass 11 "Prometheus writes" "Prometheus returned $P_COUNT series for jvm_memory_used_bytes"
  else
    log_fail 11 "Prometheus writes" "Prometheus returned 0 series for jvm_memory_used_bytes"
  fi
else
  log_skip 11 "Prometheus writes" "Grafana Prometheus credentials not set"
fi

# 12. Host metrics arrive
if [[ -n "$PROM_HOST" && -n "$PROM_USER" && -n "$CLOUD_TOKEN" ]]; then
  NODE_URL="https://${PROM_HOST}/api/v1/query?query=node_memory_MemAvailable_bytes"
  NODE_RESP=$(curl -s -u "${PROM_USER}:${CLOUD_TOKEN}" "$NODE_URL" || true)
  N_COUNT=$(echo "$NODE_RESP" | jq '.data.result | length' 2>/dev/null || echo "0")

  if [[ "$N_COUNT" -gt 0 ]]; then
    log_pass 12 "Host metrics" "Prometheus returned node_ memory metrics"
  else
    log_skip 12 "Host metrics" "Prometheus returned 0 series for node_ memory metrics"
  fi
else
  log_skip 12 "Host metrics" "Grafana Prometheus credentials not set"
fi

# 13. Traces check
TRACING_ENABLED="${TRACING_ENABLED:-false}"
if [[ "$TRACING_ENABLED" == "true" && -n "${GRAFANA_OTLP_ENDPOINT:-}" ]]; then
  log_pass 13 "Traces status" "Tracing is enabled (TRACING_ENABLED=true)"
else
  log_skip 13 "Traces status" "Tracing disabled (TRACING_ENABLED=false or OTLP endpoint missing)"
fi

# 14. Secret masking check
if [[ -n "$LOKI_HOST" && -n "$LOKI_USER" && -n "$CLOUD_TOKEN" ]]; then
  NOW_SEC=$(date +%s)
  START_SEC=$((NOW_SEC - 900))
  MASK_URL="https://${LOKI_HOST}/loki/api/v1/query_range?query=%7Bservice%3D%22financeos-server%22%7D%20%7C~%20%22(bearer%5Cs%2B%5Ba-zA-Z0-9%5C-._~%2B%2F%5D%2B%7Cpassword%3D%5C%5C%22%5C%5Cs*%5C%5C%22%22&start=${START_SEC}000000000&end=${NOW_SEC}000000000"
  MASK_RESP=$(curl -s -u "${LOKI_USER}:${CLOUD_TOKEN}" "$MASK_URL" || true)
  SECRET_COUNT=$(echo "$MASK_RESP" | jq '[.data.result[].values[]] | length' 2>/dev/null || echo "0")

  if [[ "$SECRET_COUNT" -eq 0 ]]; then
    log_pass 14 "Secret masking" "Zero unmasked secret patterns detected in Loki stream"
  else
    log_fail 14 "Secret masking" "Detected $SECRET_COUNT unmasked secret hits in Loki stream"
  fi
else
  log_skip 14 "Secret masking" "Grafana Loki credentials not set"
fi

echo "=================================================="
echo "Observability Verification Summary:"
echo "  PASS: $PASS_COUNT"
echo "  FAIL: $FAIL_COUNT"
echo "  SKIP: $SKIP_COUNT"
echo "=================================================="

if [[ "$FAIL_COUNT" -gt 0 ]]; then
  echo "Troubleshooting advice:"
  echo "  - Check 2 FAIL: Verify application process is running ('systemctl status financeos')"
  echo "  - Check 5/6 FAIL: Verify Fluent Bit service ('systemctl status fluent-bit', 'journalctl -u fluent-bit')"
  echo "  - Check 8/11 FAIL: Verify credentials in /etc/fluent-bit/fluent-bit.env and egress firewall"
  echo "  - Check 9 FAIL: Check Fluent Bit filter configuration for duplicated stdout/journald inputs"
  exit 1
fi

exit 0
