#!/usr/bin/env bash
# FinanceOS — final TLS cutover: bind Spring to loopback and close :8080.
# Run ONLY after all three are true:
#   (a) OCI security list allows TCP 80 + 443 (and you still see 8080 there — remove it AFTER this script);
#   (b) Vercel env API_BASE_URL=https://financeos.duckdns.org is deployed;
#   (c) `curl -I https://financeos.duckdns.org/api/v1/auth/me` from your Mac returns HTTP/2 401.
#   ssh -i ~/.ssh/oracle-oci ubuntu@129.159.22.124 'CONFIRM=yes bash -s' < deploy/cutover-close-8080.sh
set -euo pipefail
APP=/home/ubuntu/financeos-server
[ "${CONFIRM:-}" = "yes" ] || { echo "Refusing: run with CONFIRM=yes after checks (a)-(c) in the header pass."; exit 1; }

echo "== preflight: requests seen by Caddy in the last 200 log lines"
N=$(sudo tail -n 200 /var/log/caddy/access.log 2>/dev/null | grep -c '"status"' || true)
echo "   $N"
[ "${N:-0}" -gt 0 ] || { echo "!! Caddy has served nothing yet — Vercel is probably still on :8080. Aborting."; exit 1; }

cp "$APP/.env" "$APP/.env.bak-cutover-$(date +%Y%m%d-%H%M%S)"
if grep -q '^SERVER_ADDRESS=' "$APP/.env"; then
  sed -i 's/^SERVER_ADDRESS=.*/SERVER_ADDRESS=127.0.0.1/' "$APP/.env"
else
  printf '\n# Only Caddy (same host) may reach Tomcat directly\nSERVER_ADDRESS=127.0.0.1\n' >> "$APP/.env"
fi
sudo systemctl restart financeos
for _ in $(seq 1 60); do curl -fsS -m 3 http://127.0.0.1:8081/actuator/health 2>/dev/null | grep -q '"UP"' && break; sleep 3; done
curl -fsS -m 5 http://127.0.0.1:8081/actuator/health; echo
sudo ufw delete allow 8080/tcp >/dev/null 2>&1 || true
sudo ufw delete allow 8080/tcp >/dev/null 2>&1 || true   # v6 rule
sudo ss -ltnp | grep ':8080' || true
curl -sk --resolve financeos.duckdns.org:443:127.0.0.1 https://financeos.duckdns.org/api/v1/auth/me -o /dev/null -w 'via caddy -> HTTP %{http_code}\n'
echo "Done. Now remove the 8080 ingress rule from the OCI security list."
echo "Rollback: delete SERVER_ADDRESS from .env, sudo ufw allow 8080/tcp, sudo systemctl restart financeos."
