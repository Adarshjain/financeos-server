# FinanceOS Server — CI Deployment

The server is **already deployed and running** on OCI (`ubuntu@129.159.22.124`) against an
Autonomous Database, deployed by hand. This directory adds GitHub Actions so a push to `main`
replaces the JAR automatically. Nothing about the existing layout is moved.

Test commit

## Existing setup (observed 2026-08-15, do not "fix" silently)

| Thing | Value |
|---|---|
| Host | `ubuntu@129.159.22.124`, login key `~/.ssh/oracle-oci` |
| Shape | E2.1.Micro, x86_64, **952 MiB RAM, 0 swap** |
| App dir | `/home/ubuntu/financeos-server` (not a git clone — JAR is copied in) |
| JAR | `target/backend-1.0.0.jar` |
| Unit | `/etc/systemd/system/financeos.service`, `EnvironmentFile=<app dir>/.env` |
| Port | **8080**, exposed directly to the internet via ufw |
| Wallet | `/home/ubuntu/financeos-server/wallet`, passed as `-Doracle.net.tns_admin` |
| Firewall | **ufw** (allows 22 + 8080 only) |
| Java | OpenJDK 21.0.7 |
| Apache2 | running on :80, stock default vhost, no proxy rules, port not open in ufw — unused |

Note: `ojdbc11` alone reads the Autonomous wallet here. `oraclepki` is **not** required —
the running JAR has never contained it.

## Code changes this required

- `spring-boot-starter-actuator` added to `pom.xml`. `SecurityConfig` already had
  `/actuator/health` in its `permitAll` list, but the dependency was missing, so the endpoint
  returned `INTERNAL_ERROR`. CI polls it after restart.
- `management.*` block in `application.yml` pinning exposure to `health` with no details,
  since the endpoint is unauthenticated and 8080 is public.

## One-time setup

### 1. Grant CI restart rights on the VM

```bash
ssh -i ~/.ssh/oracle-oci ubuntu@129.159.22.124 'bash -s' < deploy/enable-ci.sh
```

### 2. Create a dedicated deploy key

Not your `oracle-oci` login key — the private half goes into GitHub, and this one can be revoked
without touching your own access.

```bash
ssh-keygen -t ed25519 -f ~/.ssh/financeos-deploy -N "" -C "github-actions-deploy"
ssh-copy-id -i ~/.ssh/financeos-deploy.pub -o IdentityFile=~/.ssh/oracle-oci ubuntu@129.159.22.124
ssh -i ~/.ssh/financeos-deploy ubuntu@129.159.22.124 'echo deploy-key-ok'
```

### 3. Add GitHub secrets

Repo → Settings → Secrets and variables → Actions:

| Name | Value |
|---|---|
| `DEPLOY_HOST` | `129.159.22.124` |
| `DEPLOY_USER` | `ubuntu` |
| `DEPLOY_SSH_KEY` | full private key: `pbcopy < ~/.ssh/financeos-deploy` |

### 4. Create the Slack app for deploy notifications

Optional — if `SLACK_WEBHOOK_URL` is unset the notify steps skip and the deploy is unaffected.

1. Go to <https://api.slack.com/apps> → **Create New App** → **From an app manifest**.
2. Pick the workspace, choose **YAML**, and paste `deploy/slack-app-manifest.yml`. Create.
3. Left sidebar → **Incoming Webhooks**. The manifest turns this on via
   `settings.incoming_webhooks.incoming_webhooks_enabled` — if the toggle reads *Off*, the
   manifest didn't apply; switch it on by hand.
4. Bottom of that page → **Add New Webhook to Workspace** → pick the channel the deploy messages
   should land in → **Allow**. A webhook is permanently bound to the channel chosen here; to
   change channel later add a second webhook rather than editing this one.
5. Copy the `https://hooks.slack.com/services/...` URL it now lists, and add it as the repo
   secret `SLACK_WEBHOOK_URL`.

Test it before pushing:

```bash
curl -X POST -H 'Content-Type: application/json' \
  --data '{"text":"FinanceOS deploy notifications wired up."}' \
  "$SLACK_WEBHOOK_URL"
```

For a private channel, invite the app first: `/invite @FinanceOS Deploy` in that channel.

### 5. Push

```bash
git add .github deploy pom.xml src/main/resources/application.yml
git update-index --chmod=+x .github/scripts/slack-notify.sh
git commit -m "Add CI deployment via GitHub Actions"
git push origin main
```

The workflow builds, runs all 323 tests, copies `backend-1.0.0.jar` (~88 MB) to the VM keeping the
previous one as `.jar.prev`, restarts the service, then polls `/actuator/health` for up to 3 minutes,
dumping journal logs on failure.

## Rollback

The workflow leaves the previous JAR in place:

```bash
ssh -i ~/.ssh/oracle-oci ubuntu@129.159.22.124
cd /home/ubuntu/financeos-server/target
cp -f backend-1.0.0.jar.prev backend-1.0.0.jar
sudo systemctl restart financeos
```

---

## Env vars that must track the client's origin

The UI is deployed separately on Vercel at `https://financeos-client.vercel.app` — it is **not** on
this box (ufw exposes only 22 and 8080). Three vars in `$APP_DIR/.env` name that origin, and all
three have to move together whenever it changes. They are not in the repo, so a JAR deploy will not
update them:

| Var | Value | Used by |
|---|---|---|
| `GOOGLE_OAUTH_REDIRECT_URI` | `https://financeos-client.vercel.app/auth/google/callback` | `GoogleOAuthClient` — must also be registered verbatim in the Google Cloud console |
| `UI_PATH` | `https://financeos-client.vercel.app` | `GmailController`, which still 302s the browser to `/settings/gmail` |
| `CORS_ORIGINS` | `https://financeos-client.vercel.app` | `SecurityConfig`; vestigial today — `API_BASE_URL` is never exposed as `NEXT_PUBLIC_`, so the browser never calls this API directly |

`GOOGLE_OAUTH_REDIRECT_URI` must point at the **client's** callback page, never at
`/api/v1/auth/google/callback` on this host. Sending the browser here directly sets
`FINANCEOS_SESSION` on the API's origin, where the Vercel app cannot read it, and every sign-in
lands back on `/login`. See `AuthController#handleGoogleCallback`.

## Known risks on the running box

Fix the first three with `ssh -i ~/.ssh/oracle-oci ubuntu@129.159.22.124 'bash -s' < deploy/harden-vm.sh`
(restarts the app):

1. **`-Xmx1024m` on a 952 MiB box with no swap.** The max heap exceeds physical RAM, so a load
   spike gets the JVM OOM-killed rather than GC'd. → `-Xmx512m` + 2 GB swap.
2. **No swap at all.** Only ~214 MiB was available at inspection time.
3. **Apache2 running for nothing** — ~30 MB held on a 1 GB box.

Not covered by that script, decide separately:

4. **No TLS.** The API is served as plaintext HTTP on `:8080` on a public IP, so session cookies
   and login passwords cross the internet in the clear. Fix by putting a reverse proxy in front
   (Caddy gives automatic Let's Encrypt certs), opening 80/443 in ufw and in the OCI subnet
   security list, closing 8080, and setting `COOKIE_SECURE=true`.
5. **Deployed JAR was ~6 months stale** (built Feb 6, 67 MB vs. 88 MB from `main` today). The first
   CI run ships six months of accumulated migrations at once — take a DB backup before pushing.
6. **`--spring.profiles.active=prod`** is passed but no `application-prod.yml` exists anywhere.
   It is inert today; either add the file or drop the flag so it doesn't mislead.

---

## Logging & Observability (Phases 0–3)

### Management Port Authentication & Observations (Task 1.5)

With `management.server.port: 8081` bound to `127.0.0.1` in `application-prod.yml`:
- Spring Boot creates a separate child management context on port 8081.
- `SecurityConfig` permits `/actuator/health` without credentials (`permitAll()`).
- Verification confirms `curl -sf http://127.0.0.1:8081/actuator/health` returns `200 OK` with full details locally without requiring authentication (enabling CI health checks).
- `/actuator/loggers` is accessible on `127.0.0.1:8081` over an SSH tunnel for runtime log level adjustments without redeploying.

### VM Setup (Phase 0 Execution Commands)

Run the following scripts on the target OCI VM:

```bash
# 1. Setup 2 GB swapfile & swappiness
ssh -i ~/.ssh/oracle-oci ubuntu@129.159.22.124 'bash -s' < deploy/add-swap.sh

# 2. Install Fluent Bit from official apt repo & enable service
ssh -i ~/.ssh/oracle-oci ubuntu@129.159.22.124 'bash -s' < deploy/install-fluent-bit.sh

# 3. Update systemd service definition (JVM OOM exit & GC logging)
scp -i ~/.ssh/oracle-oci deploy/financeos.service ubuntu@129.159.22.124:/tmp/financeos.service
ssh -i ~/.ssh/oracle-oci ubuntu@129.159.22.124 'sudo cp /tmp/financeos.service /etc/systemd/system/financeos.service && sudo systemctl daemon-reload && sudo systemctl restart financeos'
```

### Fluent Bit Configuration (Phase 3 Execution Commands)

Deploy Fluent Bit configs and environment secrets:

```bash
# Copy configuration files
scp -i ~/.ssh/oracle-oci deploy/fluent-bit.conf ubuntu@129.159.22.124:/tmp/fluent-bit.conf
scp -i ~/.ssh/oracle-oci deploy/parsers.conf ubuntu@129.159.22.124:/tmp/parsers.conf

ssh -i ~/.ssh/oracle-oci ubuntu@129.159.22.124 bash -se <<'EOF'
  sudo cp /tmp/fluent-bit.conf /etc/fluent-bit/fluent-bit.conf
  sudo cp /tmp/parsers.conf /etc/fluent-bit/parsers.conf
  sudo mkdir -p /var/log/flb-storage /etc/systemd/system/fluent-bit.service.d
EOF

# Setup environment override for
#   GRAFANA_LOKI_HOST=logs-prod-006.grafana.net
#   GRAFANA_PROM_HOST=prometheus-prod-01-prod-us-east-0.grafana.net
#   GRAFANA_LOKI_USER=123456
#   GRAFANA_PROM_USER=654321
#   GRAFANA_CLOUD_TOKEN=<access_policy_token>
#   GRAFANA_OTLP_ENDPOINT=https://otlp-gateway-prod-us-east-0.grafana.net
#   GRAFANA_OTLP_AUTH=YmFzZTY0KGluc3RhbmNlSUQ6dG9rZW4p
#
# Note: GRAFANA_OTLP_AUTH is base64(<OTLP_instanceID>:<token>). It uses a distinct instance ID
# from Loki and Prometheus. In Grafana UI, configure Loki->Tempo derived field on traceId.
#
# Then configure systemd override (/etc/systemd/system/fluent-bit.service.d/override.conf):
#   [Service]
#   EnvironmentFile=/etc/fluent-bit/fluent-bit.env

# Start Fluent Bit service
ssh -i ~/.ssh/oracle-oci ubuntu@129.159.22.124 'sudo systemctl daemon-reload && sudo systemctl restart fluent-bit && sudo systemctl status fluent-bit'
```

### Observability End-to-End Verification Harness

Run the automated verification harness directly on the deployment VM:

```bash
ssh -i ~/.ssh/oracle-oci ubuntu@129.159.22.124 'cd /opt/financeos && ./deploy/verify-observability.sh --verbose'
```

#### Expected Healthy Output:

```text
[PASS] Check 1 (Swap active): Swap memory is enabled on host
[PASS] Check 2 (App health): Actuator health status is UP on 127.0.0.1:8081
[PASS] Check 3 (JSON log validity): logs/financeos.json last line parses as valid JSON
[PASS] Check 4 (Log envelope): Log envelope contains service, env, timestamp, and real version (1.0.0)
[PASS] Check 5 (Fluent Bit active): fluent-bit systemd service is active
[PASS] Check 6 (Fluent Bit config errors): No [error] lines found in recent fluent-bit logs
[PASS] Check 7 (Credentials present): All Grafana Cloud credential variables are set
[PASS] Check 8 (Loki write receipt): Loki returned 1 stream(s) for service=financeos-server
[PASS] Check 9 (No duplicate shipping): Unique requestId returned exactly 1 log record in Loki
[PASS] Check 10 (Journal-only lines): Journald systemd lifecycle lines arrived in Loki
[PASS] Check 11 (Prometheus writes): Prometheus returned series for jvm_memory_used_bytes
[PASS] Check 12 (Host metrics): Prometheus returned node_ memory metrics
[SKIP] Check 13 (Traces status): Tracing disabled (TRACING_ENABLED=false or OTLP endpoint missing)
[PASS] Check 14 (Secret masking): Zero unmasked secret patterns detected in Loki stream
==================================================
Observability Verification Summary:
  PASS: 13
  FAIL: 0
  SKIP: 1
==================================================
```

#### Troubleshooting Failure Mapping:

| Failing Check | Likely Root Cause | Remediation Action |
|---|---|---|
| Check 1 (`Swap active`) | Swap memory unallocated on VM | Run `sudo fallocate -l 1G /swapfile && sudo chmod 600 /swapfile && sudo mkswap /swapfile && sudo swapon /swapfile` |
| Check 2 (`App health`) | Backend application crashed or failed port bind | Run `sudo systemctl status financeos` and check `journalctl -u financeos -n 100` |
| Check 3 / 4 (`Log envelope`) | Logging appender misconfigured or placeholder version | Verify `logback-spring.xml` and ensure Spring Boot build-info artifact contains `version` |
| Check 5 / 6 (`Fluent Bit`) | Fluent Bit service stopped or syntax error in `.conf` | Run `sudo systemctl status fluent-bit` and validate `/etc/fluent-bit/fluent-bit.conf` syntax |
| Check 7 / 8 / 11 (`Grafana`) | Environment file missing or invalid Cloud tokens | Verify credentials in `/etc/fluent-bit/fluent-bit.env` and test egress network routing |
| Check 9 (`Duplicate shipping`) | Logstash & journald filters capturing overlapping streams | Verify `MESSAGE` key grep filter in `/etc/fluent-bit/fluent-bit.conf` |
| Check 14 (`Secret masking`) | Secret pattern added to logging without mask filter | Ensure sensitive parameters pass through `SecretMaskingTest` regex patterns |

### SSH Tunnel for Management Actuator Port

Management endpoints are bound exclusively to loopback (`127.0.0.1:8081`) via a dedicated `@Order(0)` security chain permitting requests on port 8081. Over the SSH tunnel, actuator endpoints (including `/actuator/loggers`, `/actuator/health`, `/actuator/prometheus`) are reachable directly without credentials:

```bash
# Establish tunnel from laptop to VM loopback management port
ssh -L 8081:127.0.0.1:8081 ubuntu@129.159.22.124

# In another terminal: inspect current logger levels
curl -s http://127.0.0.1:8081/actuator/loggers/com.financeos

# Dynamically change logger level to DEBUG (no restart required)
curl -i -X POST -H 'Content-Type: application/json' \
  -d '{"configuredLevel":"DEBUG"}' \
  http://127.0.0.1:8081/actuator/loggers/com.financeos

# Reset back to INFO when finished
curl -i -X POST -H 'Content-Type: application/json' \
  -d '{"configuredLevel":"INFO"}' \
  http://127.0.0.1:8081/actuator/loggers/com.financeos
```

### Starter LogQL Queries for Grafana Cloud

1. **Find log by `errorId`:**
   ```logql
   {service="financeos-server"} | json | errorId="A3F9K2QP"
   ```

2. **Trace request by `requestId`:**
   ```logql
   {service="financeos-server"} | json | requestId="5a3f12b89c0d"
   ```

3. **Errors in last hour grouped by `event`:**
   ```logql
   sum by (event) (count_over_time({service="financeos-server", level="ERROR"}[1h]))
   ```

4. **Slow HTTP requests (>1000 ms):**
   ```logql
   {service="financeos-server"} | json | slow="true"
   ```

5. **Activity by specific `userId`:**
   ```logql
   {service="financeos-server"} | json | userId="1"
   ```

6. **Journald-only lines (JVM fatal / systemd lifecycle):**
   ```logql
   {service="financeos-server"} | json | event=""
   ```

### Security & Masking Architecture Note

- Secret masking (`MaskingJsonGeneratorDecorator`) is attached to the production `JSON_FILE` appender (`logs/financeos.json`), which is shipped to Grafana Cloud Loki.
- The standard console appender stdout/stderr is captured by systemd journald (`journalctl -u financeos`) in plain text without masking.
- This design explicitly establishes the host boundary (`ubuntu@129.159.22.124`) as the security trust boundary. Plaintext logs remain local on-box for direct debugging, while all remote-shipped data in Loki is sanitized against secret exposure.
- Fluent Bit's systemd filter (`Exclude MESSAGE ^\d{4}-\d{2}-\d{2}...`) ensures unmasked console output is excluded from the systemd stream shipped to Loki.

---

## Phase 7 — Dashboards, Alerts, & Grafana Provisioning

### 1. Grafana Cloud Stack Credential Summary (4 Pairs)

Grafana Cloud uses distinct endpoints and credentials for telemetry ingestion versus management provisioning. All ingestion endpoints share one access policy token, while provisioning requires a Service Account token:

| Purpose | Endpoint Variable | Auth Credential | User / Role |
|---|---|---|---|
| **Loki Logs** | `LOKI_URL` | `LOKI_TOKEN` | Loki Instance ID / User |
| **Prometheus Metrics** | `PROM_URL` | `PROM_TOKEN` | Prometheus Instance ID / User |
| **OTLP Traces** | `OTLP_URL` | `OTLP_TOKEN` | OTLP Instance ID / User |
| **Grafana API Provisioning** | `GRAFANA_URL` | `GRAFANA_SA_TOKEN` | Service Account Token (`glsa_...`, Admin/Editor) |

> [!WARNING]
> Do not mix instance IDs or access policy tokens between ingestion endpoints and the Grafana API. Using an instance ID or Loki token for `GRAFANA_SA_TOKEN` will result in HTTP 401 Unauthorized errors.

### 2. Creating a Grafana Service Account Token

1. Log into your Grafana Cloud instance (`https://<instance-name>.grafana.net`).
2. Navigate to **Administration** $\to$ **Users and access** $\to$ **Service accounts**.
3. Click **Add service account**, name it `financeos-provisioner`, and assign the **Admin** or **Editor** role.
4. Click **Add service account token**, set expiration as needed, copy the `glsa_...` token, and store it securely.

> [!IMPORTANT]  
> **Raw Dashboard JSON Notice**: The JSON files under `deploy/grafana/dashboards/` contain template placeholders (`${DS_LOKI}`, `${DS_PROM}`, `${DS_TEMPO}`). They are **not** importable as-is through the Grafana UI **Import** menu. You must provision them via `deploy/grafana/push-grafana-config.sh`, which performs runtime datasource resolution and substitution.

### 3. Pushing Dashboards, Contact Points, and Alerts

Run `deploy/grafana/push-grafana-config.sh` to idempotently provision contact points (`slack-financeos`), folders, dashboards, and alert rules (`financeos-alert-1` through `financeos-alert-13`):

```bash
# Dry run check (validates syntax and prints actions)
GRAFANA_URL="https://your-instance.grafana.net" \
GRAFANA_SA_TOKEN="glsa_your_token_here" \
SLACK_WEBHOOK_URL="https://hooks.slack.com/services/..." \
./deploy/grafana/push-grafana-config.sh --dry-run

# Live provisioning push
GRAFANA_URL="https://your-instance.grafana.net" \
GRAFANA_SA_TOKEN="glsa_your_token_here" \
SLACK_WEBHOOK_URL="https://hooks.slack.com/services/..." \
./deploy/grafana/push-grafana-config.sh
```

### 4. Slack Contact Point Configuration (`slack-financeos`)

1. In Grafana Cloud UI, navigate to **Alerting** $\to$ **Contact points**.
2. Click **Add contact point**, name it `slack-financeos`.
3. Choose **Slack** integration, paste your `SLACK_WEBHOOK_URL` (from step 4 of GitHub Secrets setup).
4. Save contact point and set default notification policy to route `FinanceOS` alert folder to `slack-financeos`.

### 5. Loki $\to$ Tempo Derived Field Correlation Setup

To make log lines with trace IDs clickable directly through to Tempo traces in Grafana:

1. Navigate to **Connections** $\to$ **Data sources** $\to$ **Loki**.
2. Scroll to **Derived fields** and click **Add derived field**.
3. Set **Name** to `traceId`.
4. Set **Regex** to `"traceId":"([a-f0-9]{32})"` (or `traceId=([a-f0-9]{32})`).
5. Set **Internal link** toggle to **On**.
6. Select **Tempo** as the target data source.
7. Save data source. Log lines containing a `traceId` will now display a direct link opening the corresponding trace in Tempo.

### 6. Alert Rule Reference Matrix (13 Rules)

| # | Alert Title | Condition | For | Severity | Summary & First Action |
|---|---|---|---|---|---|
| 1 | **ERROR burst** | `sum(count_over_time({service="financeos-server", level="ERROR"}[5m])) > 5` | 0m | `warning` | ERROR count > 5 in 5m. Check Service Health dashboard log panel for `errorId` and stack trace. |
| 2 | **LLM chain exhausted** | `event="llm.chain.exhausted" > 0 in 15m` | 0m | `warning` | All LLM fallback providers failed. Check LLM Providers dashboard for 429/401 API status codes. |
| 3 | **Dead-man's switch** | `absent_over_time({service="financeos-server"}[10m])` | 0m | `critical` | Server telemetry absent for >10m (OOM or Crash). Check `systemctl status financeos` on VM. |
| 4 | **Heap pressure** | `jvm_memory_used / jvm_memory_max > 0.9` | 5m | `critical` | JVM Heap usage > 90% for 5m. Inspect heap dump or increase Java heap allocation. |
| 5 | **Host memory low** | `node_memory_MemAvailable_bytes < 80MB` | 2m | `critical` | Host available RAM < 80MB. Run `deploy/add-swap.sh` or inspect VM process memory. |
| 6 | **Disk low** | `avail_bytes / size_bytes < 0.15` | 5m | `critical` | Disk space < 15%. Clean log files or expand volume storage on host. |
| 7 | **DB pool starved** | `hikaricp_connections_pending > 0` | 2m | `warning` | Hikari pool connection waiting. Check slow SQL queries in Pipelines dashboard. |
| 8 | **Gmail ingest stalled** | `time() - last_success > 6h` (or absent) | 0m | `warning` | Gmail sync stalled >6h or never run. Check OAuth tokens and Pipelines job outcomes. |
| 9 | **Price refresh stalled** | `time() - last_success > 26h` (or absent) | 0m | `warning` | Market price job stalled >26h or never run. Verify external financial data API. |
| 10 | **5xx ratio** | `http_5xx_rate / http_total_rate > 0.02` | 10m | `warning` | HTTP 5xx error rate > 2% for 10m. Check GlobalExceptionHandler logs in Grafana. |
| 11 | **Latency high** | `http_latency_p95 > 2.0s` | 10m | `warning` | p95 HTTP latency > 2s for 10m. Check DB connection pool and slow queries. |
| 12 | **OAuth failures** | `oauth callback failed > 3 in 10m` | 0m | `warning` | >3 OAuth callback errors. Check OAuth redirect URI and consent screen settings. |
| 13 | **Config suspect at boot** | `app.config.suspect > 0 in 1h` | 0m | `warning` | Booted with suspicious config. Check startup logs for invalid cookie origins / URIs. |
| 14 | **Client -> API latency high** | `quantile_over_time(0.95, client.api.call [10m]) > 3000ms` | 10m | `warning` | Client-to-API Vercel-to-OCI hop p95 latency > 3s over 10m. Check network and API response. |
| 15 | **Client action failures** | `count_over_time(client.action.failed [10m]) > 5` | 0m | `warning` | Client action failure count > 5 in 10m. Check Client Health dashboard errorId table. |

### 7. Synthetic Uptime Check (Vercel Platform Blind Spot)

To catch total Vercel deployment outages (Vercel function timeouts, cold-start failures, build failures) that cannot be captured by in-app telemetry:

1. In Grafana Cloud UI, navigate to **Synthetic Monitoring** $\to$ **Checks**.
2. Click **Create check**, choose **HTTP** check type.
3. Set **Target URL** to your production client URL (`https://<your-vercel-app>.vercel.app/login`).
4. Set **Check frequency** to `5m`.
5. Under **Alerting**, select contact point `slack-financeos`.
6. Save check. (Note: Grafana Cloud includes free tier synthetic check allowances).

### 8. Grafana Faro Frontend Observability Alert Routing (One-Click UI Fix)

When Grafana Faro Frontend Observability is enabled, Grafana Cloud automatically creates the `Errors Count - FinanceOS Client` alert rule under an app-managed namespace. App-managed rules return HTTP 400 if updated via the provisioning API.

To route Faro browser error alerts to Slack:
1. Navigate to **Alerting** $\to$ **Alert rules**.
2. Find `Errors Count - FinanceOS Client`.
3. Click **Edit**, scroll to **Notification settings**, and set **Contact point** to `slack-financeos`.
4. Click **Save rule**.



