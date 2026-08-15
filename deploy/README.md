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

### 4. Push

```bash
git add .github deploy pom.xml src/main/resources/application.yml
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
