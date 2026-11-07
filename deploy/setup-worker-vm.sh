#!/usr/bin/env bash
# One-time (idempotent) setup of the worker VM `financeos-worker` (Ubuntu 24.04 Minimal, 1 GB, cgroup v2).
# Does NOT start the app: the JAR and secrets arrive later (copy-worker-secrets.sh, then a CI deploy).
#
#   ssh -i ~/.ssh/oracle-oci ubuntu@152.70.68.140 'bash -s' < deploy/setup-worker-vm.sh
#
# Keep the unit's JVM flags identical to deploy/financeos.service (the API VM).
set -euo pipefail

APP=/home/ubuntu/financeos-server
export DEBIAN_FRONTEND=noninteractive

echo "== 1. Packages (Java 21 headless, curl, gnupg) =="
sudo apt-get update -y
sudo apt-get install -y openjdk-21-jre-headless ca-certificates-java curl gnupg ca-certificates
CACERTS=/etc/ssl/certs/java/cacerts
if ! keytool -list -keystore "$CACERTS" -storepass changeit -storetype pkcs12 >/dev/null 2>&1; then
  echo "cacerts missing or not pkcs12; regenerating"
  sudo update-ca-certificates -f
fi
keytool -list -keystore "$CACERTS" -storepass changeit -storetype pkcs12 | head -n 2
java -version 2>&1 | head -n 1

echo "== 2. 2 GB swapfile (same logic as add-swap.sh) =="
if [ "$(free -m | awk '/^Swap:/ {print $2}')" -eq 0 ]; then
  if [ ! -f /swapfile ]; then
    sudo fallocate -l 2G /swapfile || sudo dd if=/dev/zero of=/swapfile bs=1M count=2048
    sudo chmod 600 /swapfile
    sudo mkswap /swapfile
  fi
  sudo swapon /swapfile || true
fi
grep -q '/swapfile' /etc/fstab || echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab >/dev/null
echo 'vm.swappiness=10' | sudo tee /etc/sysctl.d/99-swap.conf >/dev/null
sudo sysctl -q -p /etc/sysctl.d/99-swap.conf || true

echo "== 3. App directory =="
mkdir -p "$APP/target" "$APP/logs" "$APP/wallet"
chmod 700 "$APP/wallet"

echo "== 4. systemd unit (financeos.service, role comes from APP_ROLE in .env) =="
sudo tee /etc/systemd/system/financeos.service >/dev/null <<'UNIT'
[Unit]
Description=FinanceOS Backend (worker)
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=ubuntu
WorkingDirectory=/home/ubuntu/financeos-server
EnvironmentFile=/home/ubuntu/financeos-server/.env

Environment="TNS_ADMIN=/home/ubuntu/financeos-server/wallet"
Environment="MALLOC_ARENA_MAX=2"
ExecStart=/usr/bin/java -Xmx320m -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -XX:ReservedCodeCacheSize=128m -XX:MetaspaceSize=128m -Xss512k \
  -XX:MinHeapFreeRatio=10 -XX:MaxHeapFreeRatio=30 -XX:SweeperThreshold=50 \
  -XX:+ExitOnOutOfMemoryError \
  -Xlog:gc*:file=/home/ubuntu/financeos-server/logs/gc.log:time,uptime:filecount=3,filesize=5M \
  -Djava.net.preferIPv4Stack=true \
  -Djava.net.preferIPv6Addresses=false \
  -Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts \
  -Djavax.net.ssl.trustStorePassword=changeit \
  -Djavax.net.ssl.trustStoreType=pkcs12 \
  -Doracle.net.tns_admin=/home/ubuntu/financeos-server/wallet \
  -jar /home/ubuntu/financeos-server/target/backend-1.0.0.jar --spring.profiles.active=prod

Restart=on-failure
RestartSec=10
StandardOutput=journal
StandardError=journal
SyslogIdentifier=financeos

NoNewPrivileges=true
PrivateTmp=true

[Install]
WantedBy=multi-user.target
UNIT
# cgroup v2: keep the JVM out of swap (VM1, cgroup v1, does this with memory.swappiness=0 instead).
sudo mkdir -p /etc/systemd/system/financeos.service.d
sudo tee /etc/systemd/system/financeos.service.d/no-swap.conf >/dev/null <<'DROPIN'
[Service]
MemorySwapMax=0
DROPIN
sudo systemctl daemon-reload
sudo systemctl enable financeos

echo "== 5. Passwordless restart for CI (same rule as enable-ci.sh) =="
echo 'ubuntu ALL=NOPASSWD: /usr/bin/systemctl restart financeos, /usr/bin/journalctl -u financeos *' \
  | sudo tee /etc/sudoers.d/financeos-deploy >/dev/null
sudo chmod 440 /etc/sudoers.d/financeos-deploy
sudo visudo -c -f /etc/sudoers.d/financeos-deploy

echo "== 6. journald capped at 64M =="
sudo mkdir -p /etc/systemd/journald.conf.d
printf '[Journal]\nSystemMaxUse=64M\n' | sudo tee /etc/systemd/journald.conf.d/size.conf >/dev/null
sudo systemctl restart systemd-journald

echo "== 7. rpcbind off =="
for u in rpcbind.service rpcbind.socket; do
  sudo systemctl disable --now "$u" >/dev/null 2>&1 || true
  sudo systemctl mask "$u" >/dev/null 2>&1 || true
done

echo "== 8. Fluent Bit (installed + enabled, not started; config and credentials come from copy-worker-secrets.sh) =="
if ! command -v fluent-bit >/dev/null 2>&1 && [ ! -x /opt/fluent-bit/bin/fluent-bit ]; then
  curl -fsSL https://packages.fluentbit.io/fluentbit.key | gpg --dearmor | sudo tee /usr/share/keyrings/fluentbit-keyring.gpg >/dev/null
  # Minimal images have no lsb_release; read the codename from os-release (noble on 24.04).
  # shellcheck disable=SC1091
  CODENAME=$(. /etc/os-release && echo "${VERSION_CODENAME:-noble}")
  echo "deb [signed-by=/usr/share/keyrings/fluentbit-keyring.gpg] https://packages.fluentbit.io/ubuntu/${CODENAME} ${CODENAME} main" \
    | sudo tee /etc/apt/sources.list.d/fluentbit.list >/dev/null
  sudo apt-get update -y
  sudo apt-get install -y fluent-bit
fi
sudo mkdir -p /etc/fluent-bit /var/log/flb-storage /etc/systemd/system/fluent-bit.service.d
sudo tee /etc/systemd/system/fluent-bit.service.d/override.conf >/dev/null <<'OVR'
[Service]
EnvironmentFile=/etc/fluent-bit/fluent-bit.env
OVR
sudo systemctl daemon-reload
sudo systemctl enable fluent-bit
FLB=$(command -v fluent-bit || echo /opt/fluent-bit/bin/fluent-bit)
"$FLB" --version | head -n 1

echo "== Done. Next: deploy/copy-worker-secrets.sh from your Mac. =="
free -m
