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
    sudo fallocate -l 2G /swapfile || sudo dd if=/dev/zero of=/swapfile bs=1M count=2048
    sudo chmod 600 /swapfile
    sudo mkswap /swapfile
fi

echo "Enabling swap..."
sudo swapon /swapfile || true

if ! grep -q '/swapfile' /etc/fstab; then
    echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab >/dev/null
fi

echo "Setting vm.swappiness=10..."
sudo mkdir -p /etc/sysctl.d
echo 'vm.swappiness=10' | sudo tee /etc/sysctl.d/99-swap.conf >/dev/null
sudo sysctl -p /etc/sysctl.d/99-swap.conf || true

echo "=== Memory after swap configuration ==="
free -m
