# Implementation Prompt — Transaction Categorization (SERVER)

You are implementing the **transaction categorization** feature in the FinanceOS server (this repository — Spring Boot 3 / Java, Maven, PostgreSQL, Flyway, Lombok). A matching frontend prompt exists for the client repo; the API contract in section 8 is shared between both and must be implemented **exactly as written**.

## 0. Ground rules — read first

- **Do not invent anything.** Every file, class, endpoint, and pattern referenced in this prompt exists and was verified. Before editing any file, READ it fully. Before creating a class, check whether an equivalent exists.
- **If anything in this prompt contradicts what you find in the code, or an instruction is ambiguous, STOP and ask a question.** Do not guess, do not silently pick an interpretation.
- **Do not refactor unrelated code.** Touch only what this feature requires.
- Follow existing conventions exactly: Lombok `@Getter/@Setter`, `@JdbcTypeCode(SqlTypes.VARCHAR)` for UUID columns, Hibernate `@Filter(name = "userFilter", condition = "user_id = :userId")` on user-owned entities, `UserContext.getCurrentUserId()` (in `com.financeos.core.security`) for ownership checks, record-based DTOs in `api/*/dto`.
- Migrations: Flyway SQL files in `src/main/resources/db/migration/`. The latest is `V25__add_last_statement_date_to_accounts.sql` — verify that is still the highest number before creating `V26`. Mirror the DDL style of existing migrations (look at `V7__refactor_transaction_categories_to_join_entity.sql` for a join-table example).
- Verify your work compiles and tests pass: `./mvnw compile` and `./mvnw test`.

## 1. Feature overview

When a transaction is created by any ingestion path, its description is auto-categorized:

1. Normalize the description deterministically (section 4).
2. Look for a matching **CategoryRule** (a per-user merchant→categories mapping) via contains-match on a normalized `merchant_key` (section 5).
3. **Hit on a verified rule** → apply the rule's categories; no review needed for categorization.
4. **Hit on an unverified rule** → apply the rule's categories, but flag the transaction for review with reason `CATEGORY_UNVERIFIED`.
5. **Miss** → call Gemini (section 6) to extract a merchant key + pick the applicable categories from the user's existing category set. Create a new **unverified** rule, apply its categories, flag `CATEGORY_UNVERIFIED`.
6. **LLM failure or "no fit"** → transaction stays uncategorized, flagged `CATEGORY_UNVERIFIED`, and **no rule is created**.

Separately, the existing single `ReviewType` state gains a **set of review reasons** so the UI can show *why* a transaction needs review (unreconciled vs. unverified category vs. duplicate suspicion), and so different subsystems can clear only their own reason.

## 2. Existing code you will integrate with (all verified to exist)

| What | Where |
|---|---|
| Transaction entity (`reviewType`, `categories` set via `TransactionCategory` join, `setCategories(Set<Category>)` helper) | `src/main/java/com/financeos/domain/transaction/Transaction.java` |
| `ReviewType` enum: `NEEDS_REVIEW, AUTO_REVIEWED, MANUALLY_REVIEWED` | `domain/transaction/ReviewType.java` |
| Category entity (per-user, unique `(user_id, name)`) | `domain/category/Category.java`, `CategoryRepository`, `CategoryService` |
| Ingestion path 1 — Gmail alerts: sets `NEEDS_REVIEW` at creation | `gmail/ingest/GmailTransactionWriter.java` (`writeTransaction`, line ~58) |
| Ingestion path 2 — statement reconciliation: promotes matched alerts to `AUTO_REVIEWED` (line ~250), creates unmatched statement lines as `NEEDS_REVIEW` (lines ~266, ~291) | `gmail/reconcile/StatementReconciliationService.java` |
| Ingestion path 3 — file upload: flags duplicate suspects `NEEDS_REVIEW` (lines ~210–231) | `domain/ingestion/FileIngestionService.java` |
| Manual transaction CRUD, `updateTransaction` (sets categories from `categoryIds`, sets `reviewType` from request), `batchReview` | `domain/transaction/TransactionService.java` |
| Search DSL field registry (`reviewType` is a filterable ENUM field, line ~44) | `domain/transaction/TransactionListQueryBuilder.java` |
| Transaction API + DTOs (`TransactionResponse` includes `reviewType`, `categories`) | `api/transaction/TransactionController.java`, `api/transaction/dto/*` |
| **Skeleton controller to REPLACE with the real implementation** | `api/rules/RulesController.java` (`/api/v1/rules`, currently TODO stubs) |
| Gemini call pattern to copy: raw `java.net.http.HttpClient`, JSON `responseSchema`, temperature 0 | `gmail/ingest/gemini/GeminiExtractor.java`, `GeminiProperties.java` (config prefix `gemini`, key `gemini.model` = `gemini-2.5-flash-lite` via `application.yml`) |
| Tests live under | `src/test/java/com/financeos/domain/...` (plain JUnit service/unit tests) |

Note: there is **no** `/api/v1/categorize` endpoint and you must **not** create one — the client has a dead reference to it that the frontend prompt removes.

## 3. Data model (migration `V26`, or next available number)

New table `category_rules`:

| column | type | notes |
|---|---|---|
| `id` | varchar(36) PK | UUID, same pattern as other entities |
| `user_id` | varchar(36) NOT NULL → users | |
| `merchant_key` | varchar(255) NOT NULL | normalized (section 4); UNIQUE `(user_id, merchant_key)` |
| `display_name` | varchar(255) | human-readable merchant name |
| `verified` | boolean NOT NULL default false | |
| `source` | varchar(20) NOT NULL | `LLM` or `USER` |
| `applied_count` | int NOT NULL default 0 | incremented each time the rule categorizes a transaction |
| `last_applied_at` | timestamp | |
| `created_at`, `updated_at` | timestamp | |

New join table `category_rule_categories` (`rule_id` → category_rules, `category_id` → categories, composite PK) — mirror the style of `transaction_categories` from V7.

New table `transaction_review_reasons` (`transaction_id` varchar(36) → transactions ON DELETE CASCADE, `reason` varchar(30), composite PK) — mapped as an `@ElementCollection` of a new enum `ReviewReason` on `Transaction`.

New column on `transactions`: `applied_rule_id` varchar(36) NULL → category_rules **ON DELETE SET NULL** — records which rule categorized the transaction (provenance for retroactive operations).

New enum `ReviewReason` (in `domain/transaction`): `UNRECONCILED`, `CATEGORY_UNVERIFIED`, `DUPLICATE_SUSPECT`, `OTHER`.

**Backfill in the same migration** for existing rows where `review_type = 'NEEDS_REVIEW'`: insert reason `UNRECONCILED` when `source IN ('gmail_transaction_alert','gmail_statement')`, else `OTHER`.

New entity `CategoryRule` + `CategoryRuleRepository` in a new package `com.financeos.domain.categorization`. Apply the `userFilter` `@Filter` like other user-owned entities.

## 4. Description normalization (deterministic, no LLM)

One utility class, e.g. `DescriptionNormalizer` in `domain/categorization`, used for BOTH rule matching and validating LLM-extracted merchant keys:

1. Uppercase.
2. Replace every non-alphanumeric character with a single space.
3. Drop tokens that contain 3 or more digits (kills ref numbers, card fragments, dates, UPI txn ids).
4. Drop pure noise tokens from a constant set: `UPI, POS, NEFT, IMPS, RTGS, ACH, REF, TXN, PAYMENT, PVT, LTD, LIMITED, PRIVATE, INDIA, WWW, COM, IN` (define as a `Set<String>` constant; keep it easy to extend).
5. Collapse whitespace, trim, cap at 255 chars.

Unit-test this class thoroughly with realistic Indian bank strings, e.g. `UPI-SWIGGY LIMITED-swiggy.stores@icici-REF510912345678` → `SWIGGY`, `POS 4123XX9128 AMAZON PAY` → `AMAZON PAY`.

## 5. Rule matching

In `CategorizationService` (new, `domain/categorization`):

- Load all of the user's rules (scale is tiny — 2 users, low thousands of transactions; in-memory matching is fine and preferred over SQL LIKE gymnastics).
- A rule matches when the normalized description **contains** the rule's `merchant_key` as a substring.
- If multiple rules match, the **longest `merchant_key` wins**; tie-break by `verified` first, then most recently `updated_at`.
- Enforce `merchant_key` length ≥ 3 everywhere a rule is created (API validation + LLM result validation) to prevent overbroad matches.

## 6. Gemini categorization (only on rule miss)

New class `GeminiCategorizer` in `domain/categorization` (or a `gemini` subpackage), copying the exact HTTP + `responseSchema` pattern of `GeminiExtractor`. Reuse `GeminiProperties` and its existing `model` property (`gemini-2.5-flash-lite`). Do not add a new SDK dependency — the existing code uses raw `HttpClient` on purpose.

**Batch-first API**: one call takes a list of `{index, description}` plus the user's category names, and returns per-item results. All ingestion paths must collect their rule-misses and make **one** Gemini call per ingestion run, not one per transaction.

Response schema per item:
- `index` (INTEGER)
- `merchantKey` (STRING) — the merchant identifier as it appears in the description
- `displayName` (STRING) — clean human-readable merchant name
- `categoryNames` (ARRAY of STRING, 1 or more items) — MUST be chosen from the provided category list; no upper cap, but instruct the model to include **only categories that genuinely apply** — never filler; in practice most merchants get one or two
- `noFit` (BOOLEAN) — true when no provided category fits

**Validation of every result (discard the item → treat as LLM failure if any check fails):**
- `categoryNames` resolve (case-insensitive) to the user's existing categories — never create a new `Category` from LLM output.
- `merchantKey`, after running through `DescriptionNormalizer`, is length ≥ 3 **and is a substring of the normalized original description** (this is the anti-hallucination check for the key itself).
- `noFit == true` → valid, but produces no rule and no categories.

On HTTP error, timeout, unparsable response, or a discarded item: the affected transactions stay uncategorized with reason `CATEGORY_UNVERIFIED` and **no rule row is created**. Never retry in a loop; log and move on (the user resolves via manual review).

Rule creation from a valid result: upsert on `(user_id, merchant_key)` — if the insert hits the unique constraint (two ingestion runs racing), re-read and use the existing rule instead of failing.

## 7. Review reasons — semantics and integration points

**Invariant: `reviewType == NEEDS_REVIEW` ⇔ `reviewReasons` is non-empty.** Centralize transitions in two service-level helpers (put them where they're reachable from all call sites — e.g. a small `ReviewStatusManager` component in `domain/transaction`, or methods on `TransactionService` — your call, but ONE place):

- `addReason(txn, reason)` → adds to the set, forces `reviewType = NEEDS_REVIEW`.
- `clearReason(txn, reason, promoteTo)` → removes the reason; if the set is now empty, sets `reviewType = promoteTo` (`AUTO_REVIEWED` or `MANUALLY_REVIEWED`).

Rewire every existing call site (each verified above):

1. **`GmailTransactionWriter.writeTransaction`** — replace bare `setReviewType(NEEDS_REVIEW)` with `addReason(UNRECONCILED)`; then run categorization (may add `CATEGORY_UNVERIFIED` and categories).
2. **`StatementReconciliationService`** —
   - Matched alert promotion (`setReviewType(AUTO_REVIEWED)`) becomes `clearReason(UNRECONCILED, AUTO_REVIEWED)` — so an alert with a pending `CATEGORY_UNVERIFIED` correctly **stays** in `NEEDS_REVIEW` after reconciliation.
   - New statement-line transactions get `addReason(UNRECONCILED)`; collect them and batch-categorize once at the end of the run.
3. **`FileIngestionService`** — the duplicate-suspect flags (both the new txns and the flagged DB txns) become `addReason(DUPLICATE_SUSPECT)`; batch-categorize the newly inserted transactions once at the end of the run.
4. **`TransactionService.updateTransaction`** — when the request sets `reviewType = MANUALLY_REVIEWED`, clear ALL reasons. Additionally, the review→rule feedback loop:
   - If the transaction has an `applied_rule_id` and the request's `categoryIds` equal the current categories → mark that rule `verified = true` (user confirmed it).
   - If the transaction has an `applied_rule_id` and the request changes the categories → the edit is a **one-off for this transaction only**: do NOT modify the rule's categories and do NOT change its `verified` status. (Rule edits happen only on the rules screen via the PUT endpoint.)
   - If there is no `applied_rule_id` → do NOT create a rule (raw descriptions make junk keys; the user can create rules on the rules screen).
5. **`TransactionService.batchReview`** with `MANUALLY_REVIEWED` — clear all reasons on each transaction; for each with an unverified `applied_rule_id`, mark that rule verified (batch-approve = confirmation).
6. **Rule verification side effect** (verify endpoint AND every path above that flips `verified` to true): find all transactions with `applied_rule_id = rule.id` carrying reason `CATEGORY_UNVERIFIED`, and `clearReason(CATEGORY_UNVERIFIED, AUTO_REVIEWED)` on each — verifying "Swiggy" once clears every pending Swiggy transaction.
7. **Rule category update** (PUT endpoint): re-apply the new categories to all transactions with `applied_rule_id = rule.id` **except** those already `MANUALLY_REVIEWED` (never stomp a manual decision or a one-off edit that was finalized by review).
8. **Rule delete**: transactions keep their categories; `applied_rule_id` goes null via FK (no Java-side cleanup needed beyond what the FK does — verify Hibernate doesn't fight this).

## 8. API contract (SHARED with the frontend — implement exactly)

Replace the skeleton `RulesController` (`/api/v1/rules`). All endpoints operate on the current user's rules only (ownership-check pattern from `TransactionService.updateTransaction`).

`RuleResponse` record:
```json
{
  "id": "uuid", "merchantKey": "SWIGGY", "displayName": "Swiggy",
  "categories": [ {"id": "uuid", "name": "Food & Dining"} ],
  "verified": false, "source": "LLM",
  "appliedCount": 12, "lastAppliedAt": "2026-07-08T10:00:00Z", "createdAt": "..."
}
```

| Endpoint | Behavior |
|---|---|
| `GET /api/v1/rules?page&size&sort&verified={true\|false\|omitted}&search=` | Spring `Page<RuleResponse>` (same paged shape as transactions). `search` does case-insensitive contains on `merchant_key` OR `display_name`. Default sort: unverified first, then `last_applied_at` desc. |
| `POST /api/v1/rules` body `{merchantKey, displayName?, categoryIds: [uuid, ...]}` | Normalizes `merchantKey` via `DescriptionNormalizer`, validates length ≥ 3, 409 on duplicate key, validates categories exist and belong to user. Creates with `verified = true`, `source = USER`. 201 + `RuleResponse`. |
| `PUT /api/v1/rules/{id}` body `{merchantKey?, displayName?, categoryIds?}` | Category change triggers retroactive re-apply (section 7.7). Returns `RuleResponse`. |
| `POST /api/v1/rules/{id}/verify` | Sets `verified = true`, runs side effect 7.6. Returns `RuleResponse`. |
| `DELETE /api/v1/rules/{id}` | 204. Transactions untouched (7.8). |

Changes to existing transaction API:
- `TransactionResponse` (`api/transaction/dto/TransactionResponse.java`): add `List<ReviewReason> reviewReasons` and `UUID appliedRuleId`.
- `TransactionListQueryBuilder`: add a filterable field `reviewReason` (ENUM, values = the four `ReviewReason` names). It lives in a child table, so it needs an `EXISTS (SELECT 1 FROM transaction_review_reasons r WHERE r.transaction_id = t.id AND r.reason = ?)` predicate — **study how the registry builds predicates first; if the `FieldMetadata` mechanism cannot express an EXISTS subquery without significant surgery, STOP and ask before restructuring it.**

## 9. Edge cases to handle explicitly

- Description null/blank (`description` is nullable) → skip categorization entirely, no reason added.
- Transaction already has categories (e.g. manual create with `categoryIds`) → skip auto-categorization.
- User has zero categories → skip the LLM (nothing to choose from), leave uncategorized with `CATEGORY_UNVERIFIED`.
- Gemini key not configured (`gemini.api-key` empty) → same as LLM failure; must not break ingestion.
- Categorization must never fail an ingestion run: wrap it so an exception logs and degrades to "uncategorized + CATEGORY_UNVERIFIED".

## 10. Tests (JUnit, under `src/test/java/com/financeos/domain/categorization/` and existing transaction test packages)

- `DescriptionNormalizer`: table of realistic bank strings → expected keys.
- Rule matching: contains semantics, longest-key-wins, verified tie-break, min-length guard.
- `CategorizationService` with a mocked `GeminiCategorizer`: verified hit / unverified hit / miss-creates-rule / LLM failure / noFit / empty category set / blank description.
- Review-reason transitions: invariant holds through add/clear; reconciliation promotion keeps txn in review when `CATEGORY_UNVERIFIED` remains; rule verification mass-clears and promotes to `AUTO_REVIEWED`; manual review with unchanged categories verifies the applied rule; manual review with changed categories leaves the rule completely untouched (one-off).
- LLM result validation: hallucinated category name discarded; merchantKey not a substring of the description discarded.

## 11. Out of scope — do NOT build

- No `/api/v1/categorize` suggestion endpoint.
- No backfill of historical transactions — no backfill endpoint, no scheduled job. Categorization applies only to transactions created after this feature ships (plus the review-reason migration backfill in section 3, which is data-only).
- No cross-user/shared rules, no regex/pattern rules, no rule import/export.
- No changes to the Gmail OAuth, matching (`TransactionMatcher`), or report modules beyond the integration points listed.

## 12. Before you start

Read, in this order: `Transaction.java`, `ReviewType.java`, `TransactionService.java`, `GmailTransactionWriter.java`, `StatementReconciliationService.java` (lines 200–310), `FileIngestionService.java` (lines 180–240), `GeminiExtractor.java`, `GeminiProperties.java`, `TransactionListQueryBuilder.java`, `RulesController.java`, migration `V7`. Then post any questions you have. Only start writing code once you have zero open questions.
