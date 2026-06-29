# Gmail Transaction Ingestion — Design Spec

**Status:** Draft / Proposed (v4 — `transaction_alert` + reconciliation both designed)
**Date:** 2026-06-29
**Scope:** Complete the Gmail → Transaction pipeline: cron fetch → **Gemini extraction** → persist (with review status) → **reconcile against statements**.
**Context:** Personal project. Single instance. INR only. ≤ 5–10 senders. No compliance/verification constraints.

---

## 0. Locked Decisions

| # | Decision |
|---|---|
| D1 | **Extraction = Google Gemini** via an **AI Studio API key**. Default to the **current top Flash model (Gemini 3.5 Flash** — now frontier-class) for **both** alert emails (1 call/email, JSON-schema output) **and** statement-document parsing; **Pro** only as a fallback if a statement PDF's layout trips Flash up. Model ids configurable (names move fast — use the current top Flash). Replaces the earlier regex rule-engine idea. |
| D2 | **Trigger = simple cron poll** (every 2h, **10:00–22:00 IST**). Pub/Sub push considered and **rejected** (too much ops); revisit only if latency matters. |
| D3 | **Fetch by Gmail query** `from:(<allowlist>) after:<lastSyncEpoch>`; store **last sync time** (reuse `gmail_sync_state.last_synced_at`). Fetch *everything since last sync*. |
| D4 | **One Gemini request per alert email** (not batched) — failure isolation, clean per-`message_id` idempotency, accuracy. |
| D5 | **Sender allowlist** filters candidates before Gemini (server-side via the `from:` query). One allowlist table holds both `TRANSACTION_ALERT` and `STATEMENT` senders (`purpose` column). |
| D6 | **Account resolution** = Gemini-extracted `last4` → `account.last4`, falling back to the sender's bound account. |
| D7 | **`reviewType`** column (separate from `isTransactionUnderMonitoring`): values `NEEDS_REVIEW | AUTO_REVIEWED | MANUALLY_REVIEWED`. |
| D8 | **Per-account watermark** `ingest_from_date` prevents duplicating separately-imported history — applied to **both** alert-row and statement-row creation. |
| D9 | **`sourcedDescription`** — not used (parked; do not store raw email there). |
| D10 | **Statement reconciliation = designed** (§4.10). Symmetric match of `NEEDS_REVIEW` alert txns vs parsed statement lines. |
| D11 | **`source` = single column**, distinct values: **`gmail_transaction_alert`**, **`gmail_statement`**, **`manual`** (`import` added later). |
| D12 | **Recon outcomes:** matched → existing alert row flips to `AUTO_REVIEWED` (**single row**, source unchanged); unmatched alert → stays `NEEDS_REVIEW`; unmatched statement line → **new** `gmail_statement` row, `NEEDS_REVIEW`. **1:1 greedy** match; statement rows idempotent per line. |
| — | Dropped: tests (deferred), compliance/CASA, distributed locking (single instance), multi-currency (INR), the `?status=under_monitoring` endpoint. |

---

## 1. Current State (recap)

Fetch plumbing exists and is cleanly layered; **parsing, persistence, and automation do not**.
- **Works:** OAuth (`gmail.readonly`, encrypted refresh token, `gmail_connections`), `GmailEngine` (full + incremental fetch), `SyncStateService` cursor, `POST /sync` (manual).
- **Missing:** automation; extraction; transaction creation; **email body is discarded** (only headers + attachment metadata captured); idempotency; background tenancy.

Reused from existing schema: `account_*_details.last4` (account resolution), `account_credit_card_details.statement_password` (statement decrypt — to be encrypted), `gmail_sync_state.last_synced_at` (cron cursor).

---

## 2. Goals & Non-Goals

### Goals
- Turn `transaction_alert` emails into txns automatically (cron + Gemini Flash), idempotently, watermark-gated.
- **Reconcile** against statements: confirm matches (`AUTO_REVIEWED`), surface gaps on both sides (`NEEDS_REVIEW`).
- Keep `GmailEngine` a **pure fetch engine**; all new logic above it.

### Non-Goals (deferred)
- Historical file (PDF/Excel) bulk import — separate effort; here we only define the watermark handoff and the `import` source value it will use.
- Pub/Sub realtime push (rejected); non-Gmail providers; direct bank feeds; tests; review-queue UX.

---

## 3. Target Architecture

```
  Cron (every 2h, 10:00–22:00 IST)   +   Manual POST /sync
                     │
                     ▼
        GmailIngestionService (orchestrator)   set UserContext(connection.user)
                     │
                     ▼
   GmailEngine.fetch (EXTENDED: body + lazy attachments)
   query: from:(allowlist) after:<lastSyncEpoch>          ── server-side sender filter (D5)
                     │
                     ▼  (per email; skip if ledger terminal)
            route by sender.purpose
        ┌────────────┴─────────────────┐
        ▼ TRANSACTION_ALERT             ▼ STATEMENT
 GeminiExtractor (Flash, 1/email)   StatementReconciliationService
 → {isTransaction, amount, …}        → decrypt (PDFBox/POI) → Gemini 3.5 Flash parse rows
        │                              → reconcile vs NEEDS_REVIEW alert txns (§4.10)
 AccountResolver (last4 → account)            │
        │                                     ▼
 watermark gate (ingest_from_date)    matched → AUTO_REVIEWED (alert row)
        │                              unmatched alert → NEEDS_REVIEW
        ▼                              unmatched line  → new gmail_statement / NEEDS_REVIEW
 GmailTransactionWriter
 source=gmail_transaction_alert, reviewType=NEEDS_REVIEW, idempotent (source_message_id)
                     │
                     ▼
   SyncStateService.last_synced_at = run time
```

### New components
| Component | Package | Responsibility |
|---|---|---|
| `GmailIngestionService` | `gmail.ingest` | Orchestrates a run per connection; routes each message by sender purpose. |
| `GeminiExtractor` | `gmail.ingest.gemini` | One Gemini Flash call per alert email → `ExtractedTransaction`. |
| `SenderAllowlistService` + `GmailSender` entity/repo | `gmail.ingest` | Allowlist (alert + statement senders); builds the `from:` query; binds default account. |
| `AccountResolver` | `gmail.ingest` | `last4` → account; fallback to sender's account. |
| `GmailTransactionWriter` | `gmail.ingest` | Persists txns (source, reviewType, watermark-gated, idempotent). |
| `ProcessedMessageLedger` + entity/repo | `gmail.domain` | Records each processed message id + outcome; idempotency. |
| `IngestionScheduler` | `gmail.ingest` | `@Scheduled` cron over connected accounts. |
| `StatementReconciliationService` + `StatementParser` | `gmail.reconcile` | Decrypt + Gemini-parse statement → reconcile (§4.10). |

---

## 4. Detailed Design

### 4.1 Capture the email body (extend the engine)
- Add `snippet`, `bodyText`, `bodyHtml` to `GmailMessage`; walk MIME tree (mirror attachment recursion) for `text/plain`/`text/html`, base64url-decode; expose HTML-stripped text.
- Attachment bytes fetched **lazily**, only for the statement flow.
- Engine stays "pure fetch"; `GmailFetchResultDto` stays header-only (no bodies to API clients).

### 4.2 Idempotency — processed-message ledger
- `gmail_processed_messages`, unique `(connection_id, gmail_message_id)`. Skip terminal; record outcome.
- Defense in depth: unique `source_message_id` on `transactions`.
- Makes the cron safe to re-run and tolerant of overlapping `after:` windows.

### 4.3 Alert extraction — Gemini Flash (one call per email) [D1, D4]
Send normalized body (subject + text) to **Gemini 3.5 Flash** (Flash-Lite is also fine — simple templated extraction) with a JSON schema:
```json
{ "isTransaction": true, "amount": 1250.00, "currency": "INR",
  "direction": "DEBIT", "date": "2026-06-29", "description": "AMAZON",
  "accountLast4": "1234", "confidence": 0.96 }
```
- `responseMimeType: application/json` + `responseSchema`; **temperature 0**.
- `isTransaction=false` → ledger `SKIPPED_NOT_TRANSACTION`.
- `isTransaction=true` with `amount`+`date` → create `gmail_transaction_alert` / `NEEDS_REVIEW` (even if low confidence — user reviews). Missing `amount`/`date` → ledger `FAILED`.
- API key from env/config (never committed); model id configurable.

### 4.4 Sender allowlist & account resolution [D5, D6]
- **Allowlist** (`gmail_senders`): user lists bank senders, each with a `purpose` (`TRANSACTION_ALERT`|`STATEMENT`) and an optional bound `account_id`. The cron builds `from:(s1 OR s2 OR …)` so only these senders are fetched/processed.
- **Account resolution order:** (1) Gemini `accountLast4` → `account_*_details.last4`; (2) sender's bound `account_id`; (3) else null → still create `NEEDS_REVIEW`.

### 4.5 Transaction write path
`TransactionService.createTransaction` is **not reused** (hardcodes `source=manual`, reads request-time `UserContext`). Add `GmailTransactionWriter` that:
- sets `source = gmail_transaction_alert` (or `gmail_statement` for created statement rows), `type` from `direction`,
- sets `reviewType = NEEDS_REVIEW`, sets `source_message_id` (idempotent),
- does **not** write raw email into `sourcedDescription` (D9),
- is **watermark-gated** (§4.9).

**Background tenancy (critical):** cron runs have no request, so `UserContext` (ThreadLocal) + `userFilter` are unset. Per connection:
```
UserContext.setCurrentUserId(connection.getUser().getId());
try { ...ingest... } finally { UserContext.clear(); }
```

### 4.6 Orchestration (one cron run)
```
for each connection where is_connected:
  UserContext.set(connection.user.id)
  try:
    q = "from:(<allowlist>) after:" + epoch(last_synced_at ?? firstBackfill)
    for each message in GmailEngine.fetch(connection, q)  (skip if ledger terminal):
        if sender.purpose == STATEMENT:  reconciliationService.process(message)   // §4.10
        else:                            processAlert(message)                     // §4.3 → write
    gmail_sync_state.last_synced_at = runStartTime   // advance only after batch durable
  finally: UserContext.clear()
```
- One DB transaction **per message**. `messages.list` + `after:` is the cursor — **no `historyId` dependency** (expiry is a non-issue).

### 4.7 Trigger — cron [D2]
- `@EnableScheduling` + `@Scheduled(cron = "0 0 10-22/2 * * *", zone = "Asia/Kolkata")`. Single instance → no lock.
- `POST /sync` runs the same pipeline (backfill/debug), returns summary counts.
- Mail after the 22:00 run is captured at 10:00 via `after:lastSync` — delayed, never lost.

### 4.8 Review status model (`reviewType`) [D7, D12]
New `review_type` column; `ReviewType { NEEDS_REVIEW, AUTO_REVIEWED, MANUALLY_REVIEWED }`; separate from `isTransactionUnderMonitoring`.

Resulting state space by source:
| source | reviewType lifecycle |
|---|---|
| `gmail_transaction_alert` | `NEEDS_REVIEW` (created) → `AUTO_REVIEWED` (statement-matched) / `MANUALLY_REVIEWED` (user) |
| `gmail_statement` | `NEEDS_REVIEW` (bank line with no alert) → `MANUALLY_REVIEWED` (user) |
| `manual` | `MANUALLY_REVIEWED` |
| `import` (later) | `AUTO_REVIEWED` (source of truth) |

### 4.9 Historical transactions & ingestion watermark [D8]
Per-account cutoff date stopping Gmail from re-creating already-imported history.
- `accounts.ingest_from_date` (DATE, nullable). `null` → ingest everything.
- On historical import up to X → set `ingest_from_date = X+1`.
- **Create-gate (both sources):** create a txn only if `date >= ingest_from_date`; alert older → `SKIPPED_BEFORE_WATERMARK`; statement line older → **not materialized** (assume history covers it — prevents seam duplicates).
- Per-account (different histories) and date-based (seam handled by recon).
- Example: import HDFC to 30 Jun 2026 → `ingest_from_date = 2026-07-01`; email/line dated 15 Mar 2026 skipped, 5 Jul 2026 created.

### 4.10 Statement reconciliation / cross-verification [D10, D11, D12]
**Trigger:** a `STATEMENT`-purpose sender emails a statement; the cron fetches it; the orchestrator routes here.

**Steps:**
1. Resolve account (sender's `account_id`); fetch the matching attachment (lazy).
2. **Decrypt locally** with the account's stored statement password (encrypted) — **PDFBox** (PDF) / **POI** (XLSX). Gemini can't decrypt, so we strip the password first.
3. **Parse** the decrypted document with **Gemini 3.5 Flash** (multimodal document understanding) → `StatementLine[] { date, amount, direction, description, balance? }`. Bump to **Pro** only if real statement layouts cause misses.
4. **Reconcile** per account, within the statement period:
   - Set A = account's `NEEDS_REVIEW` `gmail_transaction_alert` txns in the period.
   - Set B = parsed lines, **after the watermark gate** (drop lines dated `< ingest_from_date`).
   - Match key: same account, **exact amount**, same direction, **date within ±N days**. **1:1 greedy** (nearest-date first; each alert and each line used once); tie-break on description similarity.
5. **Outcomes [D12]:**
   - **Matched** → existing alert row → `AUTO_REVIEWED` (single row; statement line not materialized; source unchanged).
   - **Unmatched alert** → stays `gmail_transaction_alert` / `NEEDS_REVIEW`.
   - **Unmatched statement line** → **create** `gmail_statement` / `NEEDS_REVIEW`, idempotent on `source_message_id = <statementMsgId>:<lineIndex>`.
   - Safety against the seam: a line that matches an already-`AUTO_REVIEWED`/`import` txn in the period is a no-op (don't duplicate) — i.e. match-check spans all period txns; only `NEEDS_REVIEW` alerts get *promoted*.
6. **Idempotent & re-runnable:** statement message is in the ledger; created rows keyed per line; recon only promotes `NEEDS_REVIEW → AUTO_REVIEWED` (never downgrades).

**Config:** `gmail.reconcile.date-window-days` (default ±3); `gemini.statement-model` (default 3.5 Flash; Pro as fallback).

---

## 5. Data Model Changes (Flyway, next = **V16**, **Oracle** dialect)

**V16 — ledger**
```
gmail_processed_messages(
  id VARCHAR2(36) PK, connection_id VARCHAR2(36) FK gmail_connections ON DELETE CASCADE,
  user_id VARCHAR2(36) FK users, gmail_message_id VARCHAR2(255) NOT NULL,
  status VARCHAR2(40) NOT NULL,   -- CREATED|SKIPPED_NOT_TRANSACTION|SKIPPED_BEFORE_WATERMARK|FAILED|RECONCILED
  transaction_id VARCHAR2(36) NULL FK transactions, error VARCHAR2(2000) NULL, processed_at TIMESTAMP NOT NULL,
  CONSTRAINT uk_gmail_proc UNIQUE (connection_id, gmail_message_id))
```

**V17 — transactions: review status + traceability**
```
ALTER TABLE transactions ADD review_type VARCHAR2(32) NULL;
ALTER TABLE transactions ADD source_message_id VARCHAR2(255) NULL;   -- alert: msgId; statement line: msgId:idx
CREATE UNIQUE INDEX uk_txn_source_msg ON transactions (source_message_id);
```

**V18 — per-account watermark**
```
ALTER TABLE accounts ADD ingest_from_date DATE NULL;
```

**V19 — Gmail sender allowlist (alert + statement)**
```
gmail_senders(
  id VARCHAR2(36) PK, user_id VARCHAR2(36) FK users, name VARCHAR2(255) NOT NULL,
  sender_address VARCHAR2(320) NOT NULL, account_id VARCHAR2(36) NULL FK accounts,
  purpose VARCHAR2(20) DEFAULT 'TRANSACTION_ALERT' NOT NULL,   -- TRANSACTION_ALERT|STATEMENT
  attachment_pattern VARCHAR2(255) NULL, statement_format VARCHAR2(16) NULL,  -- STATEMENT only
  enabled NUMBER(1) DEFAULT 1 NOT NULL, created_at TIMESTAMP, updated_at TIMESTAMP)
```

**V20 — source values + statement password (recon phase)**
```
UPDATE transactions SET source = 'gmail_transaction_alert' WHERE source = 'gmail';
ALTER TABLE transactions DROP CONSTRAINT chk_transactions_source;
ALTER TABLE transactions ADD CONSTRAINT chk_transactions_source
  CHECK (source IN ('gmail_transaction_alert','gmail_statement','manual'));   -- 'import' added with that flow
ALTER TABLE account_bank_details ADD statement_password VARCHAR2(500) NULL;   -- encrypted at app layer
-- account_credit_card_details.statement_password: encrypt at app layer (EncryptedStringConverter)
```

Cursor: reuse `gmail_sync_state.last_synced_at` (no migration). `TransactionSource` enum → `{ gmail_transaction_alert, gmail_statement, manual }`.

---

## 6. API Changes
| Method | Path | Change |
|---|---|---|
| `POST` | `/api/v1/gmail/sync` | Full pipeline; returns summary counts. |
| `GET/POST/PUT/DELETE` | `/api/v1/gmail/senders` | **New.** CRUD for the allowlist (alert + statement senders via `purpose`). |
| `GET` | `/api/v1/gmail/connections` | **New.** Connections + status + `last_synced_at`. |
| `POST` | `/api/v1/gmail/disconnect` | **New.** Disconnect + clear sync state. |

---

## 7. Reliability & Error Handling
| Concern | Handling |
|---|---|
| Duplicate / overlapping window | Ledger + unique `source_message_id` (per-line for statements). |
| Gemini error / bad output | Schema-validated; on failure → `FAILED`; retried next run. |
| Missing amount/date | `FAILED`, surfaced. |
| Statement re-processed | Per-line idempotency key; recon promotes only `NEEDS_REVIEW → AUTO_REVIEWED`. |
| Seam vs historical import | Watermark gates statement-row creation; match-check spans all period txns. |
| Transient Gmail 429/5xx | Backoff + jitter; honor `Retry-After`. |
| Crash mid-run | Advance `last_synced_at` only after durable processing. |
| Token revoked (401/403) | Mark `is_connected=false`; surface "reconnect Gmail". |

## 8. Security & Privacy (light)
- Refresh tokens encrypted; **Gemini API key** in env/secret config. **Encrypt `statement_password`** (CC + bank) via `EncryptedStringConverter`.
- **No full email bodies persisted** (D9); only extracted fields + `source_message_id`. Decrypted statements parsed in-memory, not stored.
- Background tenancy via `UserContext` per connection. Keep `gmail.readonly`.

## 9. Observability
Counts (fetched/created/skipped/failed/reconciled), Gemini latency + failure rate, per-sender volume, match-rate, run duration. Structured logs with connection + message id; never log bodies/PII.

## 10. Configuration (`application.yml`)
`gmail.ingest.enabled`; `gmail.ingest.cron` (`0 0 10-22/2 * * *`, `Asia/Kolkata`); `gmail.ingest.first-backfill-days`; `gmail.reconcile.date-window-days` (±3); `gemini.api-key`; `gemini.model` (default `gemini-3.5-flash`); `gemini.statement-model` (default `gemini-3.5-flash`, Pro as fallback); `gemini.timeout`.

## 11. Testing
Deferred — added later.

---

## 12. Rollout Plan
| Milestone | Deliverable | Outcome |
|---|---|---|
| **M1 — Foundation** | Body capture; ledger (V16); `review_type`+`source_message_id` (V17); `ingest_from_date` (V18); background `UserContext`. | Data + idempotency + watermark ready. |
| **M2 — Alert extraction + write** | `gmail_senders` (V19) + CRUD; `GeminiExtractor` (Flash); `AccountResolver`; `GmailTransactionWriter`; manual `/sync` creates `gmail_transaction_alert`/`NEEDS_REVIEW`, watermark-gated. | End-to-end alerts work manually. |
| **M3 — Automation** | `@EnableScheduling` cron (2h, 10–22 IST) with `from:(allowlist) after:lastSync`. | Alerts ingest automatically. |
| **M4 — Reconciliation** | V20 (source values + statement passwords); statement routing; PDFBox/POI decrypt; Gemini Pro parse; matcher (1:1 greedy, watermark-gated); outcomes → `AUTO_REVIEWED` / new `gmail_statement`. | Alerts cross-verified by statements. |

**Order:** M1 → M2 → M3 → M4.

---

## 13. Open / Parked Questions
- **Parked (your call):** `sourcedDescription` usage (D9).
- **Separate effort:** historical PDF/Excel bulk import — consumes `ingest_from_date`, uses `source='import'` (+ constraint update) and `AUTO_REVIEWED`.
- **Revisitable defaults:** reconcile window `±3` days; statement parser = Gemini Pro (could swap to a deterministic library if layouts prove stable).

---

## Appendix — End-to-end example
**Alert (12:00 IST run):** query `from:(alerts@hdfcbank.net) after:<10:00>` → message M. Gemini Flash → `{isTransaction, amount:1250.00, DEBIT, date:2026-07-05, last4:1234, …}`. last4 → HDFC card account A; `2026-07-05 >= A.ingest_from_date(2026-07-01)` ✓ → create txn (DEBIT 1250.00, **source=gmail_transaction_alert**, reviewType=NEEDS_REVIEW, source_message_id=M). Ledger CREATED.
**Statement (month-end):** HDFC statement email → STATEMENT sender → fetch PDF → PDFBox decrypt (account A's password) → Gemini 3.5 Flash parses rows. Reconcile A's `NEEDS_REVIEW` alert txns vs lines (≥ watermark), 1:1 by amount+direction+date±3: the 2026-07-05 ₹1250 AMAZON line matches M → **M → AUTO_REVIEWED**. A ₹499 line with no alert and no existing match → **new gmail_statement / NEEDS_REVIEW** (source_message_id=`<stmtMsgId>:7`). An alert with no statement line → stays NEEDS_REVIEW.
