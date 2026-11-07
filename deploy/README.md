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
| Unit | `/etc/systemd/system/financeos.service`, `EnvironmentFile=<app dir>/.env`; drop-in `financeos.service.d/no-swap.conf` (service cgroup swappiness 0, from `deploy/keep-jvm-in-ram.sh`) |
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
dumping journal logs and rolling back on failure (see Rollback).

## Rollback (automatic)

`deploy/deploy-host.sh` rolls a host back by itself: when the new JAR is not healthy within 3 minutes it puts
`backend-1.0.0.jar.prev` back (the bad one stays as `.jar.bad`), restarts, and waits for health again. With two VMs
the workflow also rolls the worker back when the API did not end on the new JAR, so both VMs always end on one
build. The Slack result message has one line per VM (deployed / rolled back / not changed / DOWN) and says whether
both VMs run the same build; the "Version mismatch" alert fires if they differ for 15+ minutes.

Manual action is only needed when Slack says a VM is DOWN on both JARs. By hand, per VM:

```bash
DEPLOY_KEY=~/.ssh/oracle-oci   # or: ssh in and cp -f backend-1.0.0.jar.prev backend-1.0.0.jar && sudo systemctl restart financeos
HOME_DIR=$(mktemp -d); mkdir -p "$HOME_DIR/.ssh"; cp "$DEPLOY_KEY" "$HOME_DIR/.ssh/deploy_key"
HOME=$HOME_DIR deploy/deploy-host.sh 152.70.68.140 ubuntu worker rollback   # or 129.159.22.124 ... api rollback
```

### Migrations must work with the previous build

The worker deploys first and runs Flyway, so for 2-3 minutes of every deploy the API runs the previous JAR against
the new schema, and a rollback runs the previous JAR against it for good. So a migration may only add (tables,
nullable columns, indexes, data); renaming or dropping something the previous build reads waits for a later deploy
once no running code uses it. Migrations stay idempotent (a rolled-back build may see them again).

## Two-VM layout

Same JAR on both VMs; `APP_ROLE` in each `.env` picks the role: `api` (no schedulers, no job worker),
`worker` (everything), `all` (single-VM default). Caddy on the API VM fronts the API only; the worker takes no
web traffic. Both talk only to the database; the API hands work over through the job queue and
`autolink_requests`. A VM1 restart is still ~2 min of API downtime (no failover, by owner choice 2026-11-07).

| VM | Role | Address | Notes |
|---|---|---|---|
| API (`129.159.22.124`) | `api` | Spring `127.0.0.1:8080`, mgmt `127.0.0.1:8081` | Caddy, TLS, cgroup v1 |
| `financeos-worker` (`152.70.68.140`) | `worker` | Spring + mgmt on `127.0.0.1` | Ubuntu 24.04, cgroup v2, Hikari pool 6 |

### First rollout, in order (once)

The first switch must not go worker-first through CI: until VM1 restarts it runs the old role=all process, so both
VMs would run schedulers and the worker's startup sweep would mark VM1's in-flight jobs interrupted.

1. `ssh -i ~/.ssh/oracle-oci ubuntu@152.70.68.140 'bash -s' < deploy/setup-worker-vm.sh` (done 2026-11-07).
2. Push to `main` with `WORKER_HOST` still unset: CI deploys VM1 only; `APP_ROLE` is unset there, so it runs as `all`.
3. `deploy/copy-worker-secrets.sh` (streams `.env`, `wallet/`, Fluent Bit credentials, CI deploy key VM1 -> VM2; sets
   `APP_ROLE=worker` + loopback binds + pool 6 on VM2 and `APP_ROLE=api` on VM1, backing up VM1's `.env`; installs
   the new `fluent-bit.conf` (host label) on both and restarts Fluent Bit). VM1 keeps running as `all` until step 4,
   so run step 4 right after (a CI deploy in between would restart VM1 as `api` with no worker).
4. `deploy/first-cutover.sh`: copies VM1's JAR to VM2, pauses + drains VM1's jobs, starts the worker, then restarts
   VM1 as `api` (~2 min API downtime). On a worker failure it stops VM2 and resumes VM1.
5. GitHub secret `WORKER_HOST` = `152.70.68.140` (`DEPLOY_USER`/`DEPLOY_SSH_KEY` are reused). From now on CI ships
   the worker first (drain, swap, health on `localhost:8081`); if it is not healthy it is rolled back and the API is
   untouched. Then the API VM, same steps.
6. Only after both VMs run the new JAR (the `role` label exists only there, so "API down" would fire before it):
   `GRAFANA_URL=... GRAFANA_SA_TOKEN=... SLACK_WEBHOOK_URL=... deploy/grafana/push-grafana-config.sh`
   (new alerts: API down, Worker down, GC pause long, Job backlog; Host memory low and Heap high are per host/role).

Known gaps: an auto-link request is deleted before it runs, so a worker crash mid-run leaves it to the nightly pass;
user-triggered price-history backfill, history rematch and the NPS catalog bootstrap still run on the API node.

### Roll back to a single VM

1. Stop the worker first so schedulers never run twice: `ssh ubuntu@152.70.68.140 'sudo systemctl disable --now financeos'`;
   clear the `WORKER_HOST` secret so CI deploys the API VM only.
2. API VM: set `APP_ROLE=all` in `.env` (or delete the line), `sudo systemctl restart financeos`.

## Failed items: find, fix, retry

One failing item (a Gmail message, a statement, an instrument's price) never fails the whole job: it is
recorded on its own row and the rest carries on. Prerequisite: `ADMIN_EMAILS` in the VM `.env` lists your
email. The Failures tool is closed when it is blank.

1. **Alert.** Slack "Ingest item failed" names `userId`, `itemType`, `itemId` (Gmail message id or
   instrument id), `errorClass` and the root-cause `error`. "Job failed" (a whole job crashed) names
   `jobName`, `jobId`, `userId`, `rootCause`.
2. **Look.** Open the app, go to Settings → Debug → Failures. The Emails and Jobs tabs show every user's
   failures with the full stack under Details. Emails link straight to the message in Gmail.
   Logs: Loki `{service="financeos-server"} | json | event="ingest.item_failed"` (or `event="job.failed"`,
   `jobId="<id>"`).
3. **Decide.**
   - Transient (provider down, timeout): do nothing. Emails retry automatically (5 tries, 2 h → 24 h
     backoff), then show as Failed.
   - Bug (parser misread, constraint violation): reproduce locally, e.g. download the attachment and run
     `ParseStatementCli <file> --password <pw> --json`. Fix, test, push, and wait for Deploy to go green.
   - Will never work (junk mail, a statement for a card we don't track): Skip (emails) or Dismiss (jobs),
     with a reason.
4. **Retry.** After the fix is deployed, use Retry now on the item, or Retry all on the tab. Emails go back
   to the queue and one sync per user runs. Failed jobs are cloned; the original is marked "Retried as …".
   Statement jobs whose uploaded files have expired can't be retried.
5. **Confirm.** The item leaves the list, or comes back with a new error. Every admin action logs
   `event="admin.failure_action"` with your email.

## E2E Testing & Cross-Repo PR Choreography

- **Server PR against a client branch**: Add `e2e-client-ref: <ref>` on its own line in the PR description to run E2E against that branch (defaults to `main`).
- **Client PR against a server branch**: Add `e2e-server-ref: <ref>` on its own line in the PR description to run E2E against that branch (defaults to `main`).

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

### App lock (passkeys)

Two more vars in `$APP_DIR/.env` also name the client's host. Browsers bind a passkey to the page
origin, so the relying party is the UI host, not this API. Changing the UI domain voids every
enrolled passkey (users sign in with their password and turn app lock on again).

| Var | Value | Used by |
|---|---|---|
| `APP_LOCK_RP_ID` | `financeos-client.vercel.app` | `WebAuthnConfig` — the UI's host name (default `localhost`; the prod profile defaults to the prod host) |
| `APP_LOCK_ORIGINS` | `https://financeos-client.vercel.app` | `WebAuthnConfig` — exact UI origin(s), comma-separated (default `https://localhost:6970`; the prod profile defaults to the prod origin) |

Local E2E uses the defaults when the client is on `https://localhost:6970`; for an `http://` client
set `APP_LOCK_ORIGINS=http://localhost:6970` (`APP_LOCK_RP_ID=localhost`). Timing and limits
(`heartbeat-seconds`, `immediate-backstop-seconds`, `max-unlock-failures`, allowed/default "lock after")
are constants under `app.app-lock` in `application.yml`. Migration `V108__app_lock_devices.sql` is idempotent.

`GOOGLE_OAUTH_REDIRECT_URI` must point at the **client's** callback page, never at
`/api/v1/auth/google/callback` on this host. Sending the browser here directly sets
`FINANCEOS_SESSION` on the API's origin, where the Vercel app cannot read it, and every sign-in
lands back on `/login`. See `AuthController#handleGoogleCallback`.

## Known risks on the running box

Status re-checked live on 2026-09-02:

1. ~~`-Xmx1024m` with no swap~~ **RESOLVED** — unit runs `-Xmx512m -XX:+ExitOnOutOfMemoryError`, 2 GB swapfile active.
2. ~~No swap~~ **RESOLVED** (but 250–364 MB of swap is in use; see runbook below — the JVM heap gets paged out
   and Full GCs then take 2–12 s).
3. Apache2 + rpcbind idle → disabled by `tune-jvm-and-db.sh` (apache held :80, which Caddy needs).
4. **No TLS** → **IN PROGRESS**, see the runbook below. ufw already allows 80/443 (2026-09-02); the OCI
   subnet security list still blocks them (verified from outside: 443 times out).
5. Deployed JAR staleness — superseded by CI deploys.
6. `--spring.profiles.active=prod` still inert (no `application-prod.yml`).
7. **Spring Boot 3.2.1** (Dec 2023) — out of OSS support since Nov 2024; embedded Tomcat 10.1.17 has
   published CVEs since. Upgrade tracked in `plans/frontend-data-path-modernization-prompt.md` (server part).
8. **Ubuntu 20.04** — on ESM (esm-infra active), so patched, but plan a 24.04 rebuild when the A1 lands.
9. DB alias was `financeosdb_high` (3 concurrent statements, parallel DML → the V82 ORA-12839) → `_tp` via
   `tune-jvm-and-db.sh`.

---

## TLS + host tuning runbook (2026-09-02)

Three idempotent scripts, run from your Mac in this order. Each prints what it changed and how to roll back.

| Step | Command | Downtime | Depends on |
|---|---|---|---|
| 1 | `ssh -i ~/.ssh/oracle-oci ubuntu@129.159.22.124 'bash -s' < deploy/tune-jvm-and-db.sh` | ~1 min | nothing |
| 2 | `ssh -i ~/.ssh/oracle-oci ubuntu@129.159.22.124 'bash -s' < deploy/install-caddy.sh` | none (:8080 untouched) | nothing — cert is obtained via DuckDNS DNS-01 |
| 3 | OCI console → VCN → subnet security list: add ingress TCP 80 and 443 from 0.0.0.0/0 | none | — |
| 4 | Verify from outside: `curl -I https://financeos.duckdns.org/api/v1/auth/me` → `HTTP/2 401` | — | 2, 3 |
| 5 | Vercel → project env → `API_BASE_URL=https://financeos.duckdns.org` → redeploy; check `/var/log/caddy/access.log` fills | none | 4 |
| 6 | `ssh -i ~/.ssh/oracle-oci ubuntu@129.159.22.124 'CONFIRM=yes bash -s' < deploy/cutover-close-8080.sh` | ~1 min | 5 |
| 7 | OCI security list: remove the 8080 ingress rule. Google Cloud console: Gmail OAuth redirect URI → `https://financeos.duckdns.org/api/v1/gmail/oauth/callback` | none | 6 |

Notes:
- `deploy.yml` health-polls `localhost:8081` on the box, and Fluent Bit scrapes `127.0.0.1:8081`, so neither
  is affected by binding Tomcat to loopback.
- `financeos.duckdns.org` resolves to the reserved public IP; no DuckDNS cron is needed while the IP is static.
- Later, with a bought domain: change the site name in `/etc/caddy/Caddyfile`, `CORS_ORIGINS`, `UI_PATH`,
  and the Vercel custom domain. Nothing else moves.

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



