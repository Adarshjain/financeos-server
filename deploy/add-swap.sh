#!/usr/bin/env bash
set -euo pipefail

echo "=== Memory before swap configuration ==="
free -m

SWAP_TOTAL=$(free -m | awk '/^Swap:/ {print $2}')
if [ "${SWAP_TOTAL}" -gt 0 ]; then
    echo "Swap is already active (${SWAP_TOTAL} MB). Exiting."
    exit 0
fi

echo "Creating 2 GB swap file at /swapfile..."
if [ ! -f /swapfile ]; then
    fallocate -l 2G /swapfile || dd if=/dev/zero of=/swapfile bs=1M count=2048
    chmod 600 /swapfile
    mkswap /swapfile
fi

echo "Enabling swap..."
swapon /swapfile || true

if ! grep -q '/swapfile' /etc/fstab; then
    echo '/swapfile none swap sw 0 0' >> /etc/fstab
fi

echo "Setting vm.swappiness=10..."
mkdir -p /etc/sysctl.d
echo 'vm.swappiness=10' > /etc/sysctl.d/99-swap.conf
sysctl -p /etc/sysctl.d/99-swap.conf || true

echo "=== Memory after swap configuration ==="
free -m
