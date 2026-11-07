#!/usr/bin/env bash
# Copies runtime secrets and config from the API VM (VM1) to the worker VM (VM2) and sets the roles.
# Run on the owner's Mac. Everything streams VM1 -> VM2 through tar/ssh pipes; nothing lands on the Mac's disk.
#
#   deploy/copy-worker-secrets.sh
#
# Idempotent: files are overwritten, .env keys are replaced (never duplicated), deploy keys are merged.
# Env overrides: SSH_KEY, VM1, VM2, APP, DEPLOY_KEY_COMMENT.
set -euo pipefail

SSH_KEY="${SSH_KEY:-$HOME/.ssh/oracle-oci}"
VM1="${VM1:-ubuntu@129.159.22.124}"
VM2="${VM2:-ubuntu@152.70.68.140}"
APP="${APP:-/home/ubuntu/financeos-server}"
DEPLOY_KEY_COMMENT="${DEPLOY_KEY_COMMENT:-github-actions-deploy}"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

s1() { ssh -i "$SSH_KEY" -o StrictHostKeyChecking=accept-new "$VM1" "$@"; }
s2() { ssh -i "$SSH_KEY" -o StrictHostKeyChecking=accept-new "$VM2" "$@"; }

# Sets KEY=VALUE in a remote .env (replace if present, append otherwise). $1 = ssh function, rest = KEY=VALUE...
set_env() {
  local run="$1"; shift
  "$run" bash -s -- "$APP/.env" "$@" <<'REMOTE'
set -euo pipefail
f="$1"; shift
for kv in "$@"; do
  k="${kv%%=*}"
  if grep -q "^${k}=" "$f"; then sed -i "s|^${k}=.*|${kv}|" "$f"; else printf '%s\n' "$kv" >> "$f"; fi
done
chmod 600 "$f"
REMOTE
}

echo "== 1. Prepare VM2 dirs =="
s2 "mkdir -p $APP/target $APP/logs $APP/wallet && chmod 700 $APP/wallet"

echo "== 2. .env and wallet/ (VM1 -> VM2) =="
s1 "tar -C $APP -cf - .env wallet" | s2 "tar -C $APP -xf -"
s2 "chmod 600 $APP/.env && chmod 700 $APP/wallet && chmod -R go-rwx $APP/wallet"

echo "== 3. Fluent Bit credentials (VM1 -> VM2) =="
s1 "sudo tar -C /etc/fluent-bit -cf - fluent-bit.env" | s2 "sudo mkdir -p /etc/fluent-bit && sudo tar -C /etc/fluent-bit -xf - && sudo chown root:root /etc/fluent-bit/fluent-bit.env && sudo chmod 600 /etc/fluent-bit/fluent-bit.env"

echo "== 4. CI deploy key(s) (VM1 authorized_keys -> VM2, merged) =="
keys="$(s1 "grep -F '$DEPLOY_KEY_COMMENT' ~/.ssh/authorized_keys || true")"
if [ -z "$keys" ]; then
  echo "!! No '$DEPLOY_KEY_COMMENT' line in VM1 authorized_keys; add the deploy key to VM2 by hand." >&2
else
  s2 bash -s -- "$keys" <<'REMOTE'
set -euo pipefail
mkdir -p ~/.ssh && chmod 700 ~/.ssh && touch ~/.ssh/authorized_keys
while IFS= read -r line; do
  [ -n "$line" ] || continue
  grep -qxF "$line" ~/.ssh/authorized_keys || printf '%s\n' "$line" >> ~/.ssh/authorized_keys
done <<< "$1"
chmod 600 ~/.ssh/authorized_keys
REMOTE
fi

echo "== 5. Roles and pool size =="
# Worker: HTTP and management stay on loopback (no one calls it from outside; CI checks health over SSH).
# Hikari: relaxed binding of spring.datasource.hikari.*.
set_env s2 APP_ROLE=worker SERVER_ADDRESS=127.0.0.1 MANAGEMENT_SERVER_ADDRESS=127.0.0.1 \
  SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE=6 SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE=6
s1 "cp -p $APP/.env $APP/.env.bak-\$(date +%Y%m%d-%H%M%S)"
set_env s1 APP_ROLE=api

echo "== 6. Fluent Bit config (host label on metrics) =="
# Both VMs: validate the new config with the real credentials, keep a backup, then (re)start.
for target in s1 s2; do
  "$target" 'sudo mkdir -p /etc/fluent-bit /var/log/flb-storage'
  for f in fluent-bit.conf parsers.conf; do
    "$target" "sudo cp -p /etc/fluent-bit/$f /etc/fluent-bit/$f.bak 2>/dev/null || true; sudo tee /etc/fluent-bit/$f >/dev/null" < "$HERE/$f"
  done
  "$target" bash -s <<'REMOTE'
set -e
if sudo sh -c 'set -a; . /etc/fluent-bit/fluent-bit.env; /opt/fluent-bit/bin/fluent-bit --dry-run -c /etc/fluent-bit/fluent-bit.conf' >/dev/null 2>&1; then
  sudo systemctl restart fluent-bit
  echo "fluent-bit restarted: $(systemctl is-active fluent-bit)"
else
  echo "!! fluent-bit config failed --dry-run; restoring backup, NOT restarting" >&2
  for f in fluent-bit.conf parsers.conf; do [ -f /etc/fluent-bit/$f.bak ] && sudo cp -p /etc/fluent-bit/$f.bak /etc/fluent-bit/$f; done
  exit 1
fi
REMOTE
done

echo "== Done. VM1 APP_ROLE=api takes effect at its next restart: run deploy/first-cutover.sh now (a CI deploy before that would leave no worker running jobs). =="
