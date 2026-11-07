#!/usr/bin/env bash
# Installs deploy/Caddyfile as /etc/caddy/Caddyfile on the API VM: backup, validate, reload (no downtime).
# Run from your Mac:
#   scp -i ~/.ssh/oracle-oci deploy/Caddyfile ubuntu@129.159.22.124:/tmp/Caddyfile.new
#   ssh -i ~/.ssh/oracle-oci ubuntu@129.159.22.124 'bash -s' < deploy/update-caddy.sh
#
# Rollback: sudo cp /etc/caddy/Caddyfile.bak-<timestamp> /etc/caddy/Caddyfile && sudo systemctl reload caddy
set -euo pipefail

NEW="${1:-/tmp/Caddyfile.new}"
DEST=/etc/caddy/Caddyfile
[ -f "$NEW" ] || { echo "!! $NEW not found (scp deploy/Caddyfile there first)" >&2; exit 1; }

BAK="$DEST.bak-$(date +%Y%m%d-%H%M%S)"
sudo cp -p "$DEST" "$BAK"
echo "backup: $BAK"

# The unit supplies DUCKDNS_TOKEN via its EnvironmentFile; validate with the same environment.
ENVF=$(systemctl show caddy -p EnvironmentFiles --value 2>/dev/null | awk '{print $1}')
validate() {
  if [ -n "$ENVF" ] && sudo test -f "$ENVF"; then
    sudo sh -c "set -a; . '$ENVF'; caddy validate --config '$1' --adapter caddyfile"
  else
    sudo caddy validate --config "$1" --adapter caddyfile
  fi
}
validate "$NEW"

sudo cp "$NEW" "$DEST"
sudo systemctl reload caddy
sleep 2
systemctl is-active caddy
curl -sk --resolve financeos.duckdns.org:443:127.0.0.1 https://financeos.duckdns.org/api/v1/auth/me -o /dev/null -w 'via caddy -> HTTP %{http_code}\n'
