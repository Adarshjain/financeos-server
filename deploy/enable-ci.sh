#!/bin/bash
# Grants the CI deploy user the two sudo rights the GitHub Actions workflow needs,
# without a password. Everything else on the box is left exactly as it is.
#
# Run ON THE VM, once:
#   ssh -i ~/.ssh/oracle-oci ubuntu@129.159.22.124 'bash -s' < deploy/enable-ci.sh
set -euo pipefail

echo "== Sudoers rule for CI (restart + read logs only) =="
echo 'ubuntu ALL=NOPASSWD: /usr/bin/systemctl restart financeos, /usr/bin/journalctl -u financeos *' \
    | sudo tee /etc/sudoers.d/financeos-deploy > /dev/null
sudo chmod 440 /etc/sudoers.d/financeos-deploy
# A malformed sudoers file locks out sudo entirely — validate before trusting it.
sudo visudo -c -f /etc/sudoers.d/financeos-deploy

echo "== Verify =="
sudo -n systemctl is-active financeos && echo "OK: passwordless restart rights are in place."
