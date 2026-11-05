#!/usr/bin/env bash
# Keeps the API's memory out of swap and stops login-time memory spikes. Idempotent; run on the VM with sudo.
#
# Why: the VM is cgroup v1. The global vm.swappiness=10 does not reach child cgroups, so the
# financeos.service cgroup kept the default 60 and the kernel paged idle old-gen heap out to swap.
# Every full GC then read it back: 2.5-13 s stop-the-world pauses for ~0.7 s of GC work (2026-11-05).
# Setting the service cgroup's swappiness to 0 keeps the JVM resident; page cache and the small
# daemons give way instead.
#
# Also turns off the update-motd scripts that run on every SSH login (CI deploys included):
# check-new-release alone took ~90 MB and most of a CPU for several seconds.
set -euo pipefail

UNIT=financeos.service
CG=/sys/fs/cgroup/memory/system.slice/$UNIT/memory.swappiness
DROPIN_DIR=/etc/systemd/system/$UNIT.d
DROPIN=$DROPIN_DIR/no-swap.conf

sudo mkdir -p "$DROPIN_DIR"
sudo tee "$DROPIN" >/dev/null <<EOF
[Service]
# '+' runs this one command with full privileges (the service itself runs as ubuntu).
ExecStartPost=+/bin/sh -c 'echo 0 > $CG'
EOF
sudo systemctl daemon-reload

# Apply now to the running service; no restart needed.
if [ -w "$CG" ] || sudo test -e "$CG"; then
  echo 0 | sudo tee "$CG" >/dev/null
fi
echo "service swappiness: $(cat "$CG")"

for f in 50-motd-news 90-updates-available 91-release-upgrade 91-contract-ua-esm-status 95-hwe-eol; do
  [ -e "/etc/update-motd.d/$f" ] && sudo chmod -x "/etc/update-motd.d/$f"
done
sudo systemctl disable --now motd-news.timer >/dev/null 2>&1 || true
echo "motd scripts off: $(ls -l /etc/update-motd.d | awk '$1 !~ /x/ {print $NF}' | tail -n +2 | tr '\n' ' ')"
