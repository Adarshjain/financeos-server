#!/usr/bin/env bash
# FinanceOS API TLS edge — 2026-09-02
#   ssh -i ~/.ssh/oracle-oci ubuntu@129.159.22.124 'bash -s' < deploy/install-caddy.sh
#
# Installs Caddy from the official apt repo (gives us the unit, user and dirs), then swaps the binary
# for the official build that includes the DuckDNS DNS module. The Let's Encrypt certificate is then
# obtained with DNS-01, which works BEFORE ports 80/443 are opened in the OCI security list.
# Does NOT touch :8080 — the API keeps serving plaintext until deploy/cutover-close-8080.sh.
# Safe to re-run.
set -euo pipefail
DOMAIN=financeos.duckdns.org
TOKEN=$(sed -nE 's/.*token=([a-f0-9-]+).*/\1/p' /home/ubuntu/duckdns/duck.sh | head -1)
[ -n "$TOKEN" ] || { echo "!! Could not read the DuckDNS token from ~/duckdns/duck.sh"; exit 1; }

echo "== 1. apt install caddy"
if ! command -v caddy >/dev/null 2>&1; then
  sudo apt-get install -y -qq debian-keyring debian-archive-keyring apt-transport-https curl >/dev/null
  curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/gpg.key' | sudo gpg --dearmor --yes -o /usr/share/keyrings/caddy-stable-archive-keyring.gpg
  curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/debian.deb.txt' | sudo tee /etc/apt/sources.list.d/caddy-stable.list >/dev/null
  sudo apt-get update -qq
  sudo apt-get install -y -qq caddy
fi
sudo systemctl stop caddy 2>/dev/null || true

echo "== 2. binary with github.com/caddy-dns/duckdns (kept across apt upgrades via dpkg-divert)"
if ! caddy list-modules 2>/dev/null | grep -q 'dns.providers.duckdns'; then
  sudo dpkg-divert --divert /usr/bin/caddy.default --rename /usr/bin/caddy >/dev/null 2>&1 || true
  curl -fsSL -m 300 -o /tmp/caddy-duckdns "https://caddyserver.com/api/download?os=linux&arch=amd64&p=github.com/caddy-dns/duckdns"
  sudo install -m 0755 /tmp/caddy-duckdns /usr/bin/caddy
  rm -f /tmp/caddy-duckdns
fi
caddy version
caddy list-modules | grep dns.providers.duckdns

echo "== 3. config"
sudo install -m 0600 -o root -g root /dev/null /etc/caddy/duckdns.env
echo "DUCKDNS_TOKEN=$TOKEN" | sudo tee /etc/caddy/duckdns.env >/dev/null
sudo mkdir -p /etc/systemd/system/caddy.service.d
printf '[Service]\nEnvironmentFile=/etc/caddy/duckdns.env\n' | sudo tee /etc/systemd/system/caddy.service.d/override.conf >/dev/null
sudo tee /etc/caddy/Caddyfile >/dev/null <<CADDY
# FinanceOS API edge. TLS via Let's Encrypt DNS-01 (DuckDNS), so issuance does not depend on :80.
# Source of truth: financeos-server/deploy/Caddyfile
${DOMAIN} {
	encode zstd gzip
	tls {
		dns duckdns {env.DUCKDNS_TOKEN}
	}
	# Spring listens on :8080 (0.0.0.0 until cutover, 127.0.0.1 after). Caddy sets X-Forwarded-For/Proto.
	reverse_proxy 127.0.0.1:8080
	log {
		output file /var/log/caddy/access.log {
			roll_size 10MiB
			roll_keep 3
		}
		format json
	}
}
CADDY
sudo mkdir -p /var/log/caddy
# validate as root (it opens the access-log writer), then give the log dir back to the caddy service user
sudo caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile
sudo chown -R caddy:caddy /var/log/caddy

echo "== 4. free :80 and start"
sudo systemctl disable --now apache2 2>/dev/null || true
sudo systemctl daemon-reload
sudo systemctl enable --now caddy
echo "   waiting for the Let's Encrypt certificate (DNS-01 via DuckDNS, usually 20-90 s)..."
for _ in $(seq 1 30); do
  if sudo journalctl -u caddy --since "-5 min" --no-pager | grep -q 'certificate obtained successfully'; then break; fi
  sleep 5
done
sudo journalctl -u caddy --since "-5 min" --no-pager | grep -iE 'certificate obtained|obtaining|error|failed' | tail -6 | cut -c1-240 || true
systemctl is-active caddy

echo "== 5. local TLS check (works while the OCI security list still blocks 443; 401 = proxy path OK)"
curl -sk --resolve "${DOMAIN}:443:127.0.0.1" "https://${DOMAIN}/api/v1/auth/me" -o /dev/null -w 'local https -> HTTP %{http_code}\n' || true
echo "Next: open TCP 80 + 443 in the OCI subnet security list, then from your Mac:"
echo "  curl -I https://${DOMAIN}/api/v1/auth/me     # expect HTTP/2 401"
