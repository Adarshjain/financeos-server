#!/usr/bin/env bash
# FinanceOS API host tuning — 2026-09-02
# Run from your Mac (one restart, ~40–60 s of API downtime; idempotent; backs up first):
#   ssh -i ~/.ssh/oracle-oci ubuntu@129.159.22.124 'bash -s' < deploy/tune-jvm-and-db.sh
#
# What it changes and why (all measured on the live box / Grafana, 2026-09-02):
#  - DB alias financeosdb_high -> financeosdb_tp   HIGH caps concurrent statements at 3 and enables
#                                                  parallel DML (the V82 ORA-12839). TP is the OLTP service.
#  - JVM: Serial GC (explicit), C1-only JIT, bigger code cache + metaspace high-water mark, smaller
#         thread stacks. gc.log showed 2.1 s Full GCs on a 102 MB heap caused by "CodeCache GC
#         Threshold" / "Metadata GC Threshold" on a swapping 952 MB box.
#  - Tomcat 25 threads (2 users), trust X-Forwarded-* from the local Caddy.
#  - journald capped (1.5 GB on disk, ~55 MB RSS), apache2 + rpcbind disabled (apache holds :80 for Caddy).
#  - GMAIL_REDIRECT_URI -> https (the :8080 http URI dies at cutover anyway; update Google Cloud console).
set -euo pipefail
APP=/home/ubuntu/financeos-server
UNIT=/etc/systemd/system/financeos.service
TS=$(date +%Y%m%d-%H%M%S)

echo "== 1. backups"
cp "$APP/.env" "$APP/.env.bak-$TS"
sudo cp "$UNIT" "$UNIT.bak-$TS"
echo "   $APP/.env.bak-$TS"
echo "   $UNIT.bak-$TS"

echo "== 2. .env"
sed -i -E 's/@financeosdb_high\b/@financeosdb_tp/' "$APP/.env"
sed -i -E 's#^GMAIL_REDIRECT_URI=.*#GMAIL_REDIRECT_URI=https://financeos.duckdns.org/api/v1/gmail/oauth/callback#' "$APP/.env"
if ! grep -q '^SERVER_TOMCAT_THREADS_MAX=' "$APP/.env"; then
cat >> "$APP/.env" <<'ENV'

# --- 2026-09-02 tuning (2 users; Caddy terminates TLS on this host) ---
SERVER_TOMCAT_THREADS_MAX=25
SERVER_TOMCAT_THREADS_MIN_SPARE=2
# Trust X-Forwarded-* from local Caddy (Tomcat RemoteIpValve; 127.0.0.1 is an internal proxy by default)
SERVER_FORWARD_HEADERS_STRATEGY=native
ENV
fi
grep -E '^(DB_URL|GMAIL_REDIRECT_URI|SERVER_)' "$APP/.env" | sed -E 's#(DB_URL=).*(@[a-z_]+).*#\1***\2#'

echo "== 3. unit: JVM flags"
if ! grep -q 'TieredStopAtLevel' "$UNIT"; then
  sudo sed -i -E 's#^ExecStart=/usr/bin/java -Xmx512m \\#ExecStart=/usr/bin/java -Xmx512m -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -XX:ReservedCodeCacheSize=128m -XX:MetaspaceSize=128m -Xss512k \\#' "$UNIT"
fi
grep -E '^ExecStart' "$UNIT"

echo "== 4. journald cap"
sudo mkdir -p /etc/systemd/journald.conf.d
printf '[Journal]\nSystemMaxUse=64M\nRuntimeMaxUse=32M\n' | sudo tee /etc/systemd/journald.conf.d/size.conf >/dev/null
sudo systemctl restart systemd-journald
sudo journalctl --vacuum-size=64M >/dev/null 2>&1 || true

echo "== 5. stop idle services"
sudo systemctl disable --now apache2 rpcbind 2>/dev/null || true

echo "== 6. restart API"
sudo systemctl daemon-reload
sudo systemctl restart financeos
T0=$(date +%s)
for _ in $(seq 1 60); do
  if curl -fsS -m 3 http://127.0.0.1:8081/actuator/health 2>/dev/null | grep -q '"UP"'; then
    echo "   UP after $(( $(date +%s) - T0 ))s"; break
  fi
  sleep 3
done
if ! curl -fsS -m 5 http://127.0.0.1:8081/actuator/health >/dev/null; then
  echo "!! HEALTH FAILED. Last log lines:"; sudo journalctl -u financeos -n 40 --no-pager
  echo "!! ROLLBACK: cp $APP/.env.bak-$TS $APP/.env && sudo cp $UNIT.bak-$TS $UNIT && sudo systemctl daemon-reload && sudo systemctl restart financeos"
  exit 1
fi

echo "== 7. verify"
sudo journalctl -u financeos --since "-3 min" --no-pager | grep -iE 'ERROR|ORA-|exception' | head -5 || true
grep -m2 -E 'Using|CommandLine' "$APP/logs/gc.log" | cut -c1-220
free -m | head -2
echo "Done. Watch GC pause max + node_memory_MemAvailable on the service-health dashboard for a few days."
