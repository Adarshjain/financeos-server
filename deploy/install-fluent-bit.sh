#!/usr/bin/env bash
set -euo pipefail

echo "=== Installing Fluent Bit ==="

if command -v fluent-bit >/dev/null 2>&1; then
    echo "Fluent Bit is already installed:"
    fluent-bit --version
    exit 0
fi

# Add official Fluent Bit apt repository keyring & source list
curl -fsSL https://packages.fluentbit.io/fluentbit.key | gpg --dearmor | sudo tee /usr/share/keyrings/fluentbit-keyring.gpg >/dev/null

CODENAME=$(lsb_release -cs 2>/dev/null || echo "jammy")
echo "deb [signed-by=/usr/share/keyrings/fluentbit-keyring.gpg] https://packages.fluentbit.io/ubuntu/${CODENAME} ${CODENAME} main" | sudo tee /etc/apt/sources.list.d/fluentbit.list

sudo apt-get update -y
sudo apt-get install -y fluent-bit

# Enable service but do not start it yet (config lands in Phase 3)
sudo systemctl enable fluent-bit

echo "=== Fluent Bit Installation Complete ==="
fluent-bit --version
