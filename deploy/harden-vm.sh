#!/bin/bash
# Optional hardening for the existing VM. Each block is independent — read before running.
# This RESTARTS the app at the end (brief downtime).
#
#   ssh -i ~/.ssh/oracle-oci ubuntu@129.159.22.124 'bash -s' < deploy/harden-vm.sh
set -euo pipefail

echo "== 1. Add 2 GB swap =="
# The box has 952 MiB RAM and currently zero swap: any spike is an instant OOM kill.
if ! swapon --show | grep -q swapfile; then
    sudo fallocate -l 2G /swapfile
    sudo chmod 600 /swapfile
    sudo mkswap /swapfile
    sudo swapon /swapfile
    echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab > /dev/null
    # Prefer reclaiming cache over swapping the JVM heap out.
    echo 'vm.swappiness=10' | sudo tee /etc/sysctl.d/99-swappiness.conf > /dev/null
    sudo sysctl -q -p /etc/sysctl.d/99-swappiness.conf
else
    echo "swap already present, skipping"
fi

echo "== 2. Stop Apache =="
# Apache listens on :80 with only the stock default vhost, no proxy rules, and ufw does
# not even allow 80 inbound — so it serves nothing while holding ~30 MB on a 1 GB box.
if systemctl is-active --quiet apache2; then
    sudo systemctl disable --now apache2
else
    echo "apache2 not running, skipping"
fi

echo "== 3. Cap the JVM heap at 512m =="
# The unit currently sets -Xmx1024m, which exceeds total RAM.
sudo sed -i 's/-Xmx1024m/-Xmx512m/' /etc/systemd/system/financeos.service
sudo systemctl daemon-reload
sudo systemctl restart financeos

echo "== Result =="
free -h
systemctl is-active financeos
