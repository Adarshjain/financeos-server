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
