# Transaction Query API — Filter / Sort / Search (Implementation Spec)

You are adding **filter, sort, and search** to the transactions list in this Spring Boot / Oracle / Maven repo. Read this whole doc before writing code. **Do not redesign** — the decisions below are locked. If something is ambiguous or seems wrong, stop and ask the human.

> Note for the human running the agent: this is sized for a single agent (e.g. Gemini). Work milestone-by-milestone (§13) and stop for review after each.

---

## 0. Locked decisions

1. **Filter DSL, not flat params.** Reuse the report engine's `FilterClause` (`{field, operator, value}`) shape. Because it needs a JSON body, this is a **new `POST /api/v1/transactions/search`** endpoint. The existing `GET /api/v1/transactions` stays as the unfiltered default.
2. **Amount is filtered on the SIGNED value** (CREDIT positive, DEBIT negative) — the value the API returns, not the stored unsigned magnitude. This is already how `amount` maps in `TransactionQueryBuilder.SIGNED_AMOUNT`.
3. **Reuse strategy = extract & share.** Pull the per-type predicate logic out of `TransactionQueryBuilder` into a catalog-agnostic helper (`SqlPredicates`) that **both** the existing report builder and the new list builder call. One source of truth for operator → SQL semantics.
4. **Search is broad:** case-insensitive match across `description`, `sourced_description`, **account name**, and **category names**.
5. Response shape is **unchanged**: `Page<TransactionResponse>`, including the running `balance`.

---

## 1. Stack & conventions (match existing code)

- Java + Spring Boot, build with `./mvnw`. DB is **Oracle** (`OracleDialect`).
- JPA/Hibernate. Dynamic native SQL via `EntityManager` in a **custom repository fragment** (see §9).
- Multi-tenancy: every query MUST be scoped to `UserContext.getCurrentUserId()`. Native SQL bypasses the Hibernate `userFilter`, so the WHERE clause MUST pin `t.user_id = :userId` explicitly (the existing report builder and balance query already do this — keep it).
- DTOs are Java `record`s. Reuse `com.financeos.domain.report.definition.FilterClause` directly for the request filters.

---

## 2. THE correctness rule (running balance) — read twice

`GET /api/v1/transactions` is not a plain query. `TransactionService.getAllTransactions` runs a two-phase fetch backed by a **window function** that computes each transaction's true running balance over its account's *entire* ledger:

```
balance = opening_balance + SUM(signed_amount)
            OVER (PARTITION BY account_id ORDER BY transaction_date, created_at, id)
```

Pagination/sort are applied to a wrapper (`sub`) around that window query.

**Filters, search, sort, and pagination MUST be applied to the OUTER wrapper — never inside the window subquery.**

- ✅ Outer: the window still sees the full per-account ledger, so every returned row keeps its **true** balance, and we just choose which of those rows to return.
- ❌ Inner: the window would only see the filtered rows, so `balance` becomes a meaningless running sum of the subset. **This is the #1 thing to get wrong. Do not do it.**

(An `accountId` filter happens to be safe either place because balance is partitioned by account — but for uniformity, **all** filters go on the outer wrapper.)

The **count query does NOT need the window** — it's a filtered `count(*)`. See §8.

Phase 2 (hydrate entities by id via `@EntityGraph`, re-map balances, restore order) stays **exactly as it is today**.

---

## 3. API contract

### `POST /api/v1/transactions/search`
- **Auth:** `sessionAuth` (same as all transaction endpoints).
- **Query params:** `page` (default 0), `size` (default 50), `sort` (default `date,desc`) — same `Pageable` convention as `POST /api/v1/reports/data`.
- **Body:**

```json
{
  "filters": [
    {"field": "type",     "operator": "is",          "value": "DEBIT"},
    {"field": "amount",   "operator": "between",      "value": {"from": -500, "to": -50}},
    {"field": "date",     "operator": "last_x_days",  "value": 30},
    {"field": "category", "operator": "in",           "value": ["Food", "Travel"]},
    {"field": "accountId","operator": "is",           "value": "9d3f...uuid"}
  ],
  "search": "coffee"
}
```

- `filters[]` are **AND-ed**. `value` is a raw `JsonNode` whose shape depends on the operator (scalar / array / `{from,to}` / absent for valueless relative-date ops) — identical to reports.
- `search` is optional free-text (§5). Empty/blank `search` and empty/absent `filters` are both valid (an empty body behaves like today's `GET`).
- **Response:** `200` with `Page<TransactionResponse>` — identical to the existing `GET`.

Define a request record:

```java
public record TransactionSearchRequest(List<FilterClause> filters, String search) {}
```

### `GET /api/v1/transactions` — unchanged externally
Refactor it to delegate to the same shared query path with empty criteria (§9). Keep its behavior identical (it currently shows **all** transactions, including excluded — preserve that; `isExcluded` is an optional filter, never a default).

---

## 4. Field catalog for the list

The list has its **own** field map (it does NOT reuse `DatasourceCatalog.FIELDS`, because that omits `accountId`/`reviewType` and collapses `source`). Validate operators with the **type-only** helper `DatasourceCatalog.operatorsFor(FieldType)`, which is data-agnostic and reusable.

| field (API)         | FieldType | Outer-wrapper SQL expression                 | Join needed | Notes |
|---------------------|-----------|----------------------------------------------|-------------|-------|
| `amount`            | NUMBER    | `sub.signed_amount`                          | —           | Signed; see §8 inner select |
| `date`              | DATE      | `sub.transaction_date`                       | —           | Supports relative ops |
| `type`              | ENUM      | `sub.type`                                   | —           | `DEBIT`, `CREDIT` |
| `source`            | ENUM      | `sub.source`                                 | —           | **Real enum values** (see ⚠️ below) |
| `description`       | STRING    | `sub.description`                            | —           | |
| `accountId`         | ENUM      | `sub.account_id`                             | —           | `is` / `is_not` / `in` / `not_in`; binds UUID strings |
| `account`           | ENUM      | `a.name`                                     | ACCOUNTS    | Filter by account name (optional) |
| `accountType`       | ENUM      | `a.type`                                     | ACCOUNTS    | `bank_account`/`credit_card`/`stock`/`mutual_fund`/`generic` |
| `category`          | ENUM      | EXISTS subquery on `sub.id`                  | (EXISTS)    | Many-to-many — never a JOIN (would fan out) |
| `reviewType`        | ENUM      | `sub.review_type`                            | —           | `NEEDS_REVIEW`/`AUTO_REVIEWED`/`MANUALLY_REVIEWED` |
| `isUnderMonitoring` | BOOLEAN   | `sub.is_under_monitoring`                    | —           | |
| `isExcluded`        | BOOLEAN   | `sub.is_excluded`                            | —           | |

⚠️ **`source` value gap — fix, don't inherit.** `DatasourceCatalog` advertises `source` as `["gmail","manual"]`, but the real `TransactionSource` enum / DB values are `gmail_transaction_alert`, `gmail_statement`, `manual`. This list API is a precise data endpoint, so it filters on the **real enum values**. Do not reuse the report's collapsed `source` set here.

Operators per type come from the existing sets (see `DatasourceCatalog`):
- NUMBER: `equals`, `greater_than`, `less_than`, `between`
- STRING: `exact`, `starts_with`, `ends_with`, `contains`, `in`
- ENUM: `is`, `is_not`, `in`, `not_in`
- BOOLEAN: `is`
- DATE: absolute (`is`,`after`,`before`,`between`) + relative (`this_month`,`last_x_days`,`current_fy`, … via `DateRangeResolver`)

---

## 5. Search

When `search` is non-blank, add **one** AND-ed predicate that OR's a case-insensitive `LIKE` across all sources. Reuse the escaping helpers from `SqlPredicates` (`escapeLike` + `ESCAPE '\'`). Bind once: `:q = "%" + escapeLike(term.toLowerCase()) + "%"`.

```sql
(
      LOWER(sub.description)        LIKE :q ESCAPE '\'
   OR LOWER(sub.sourced_description) LIKE :q ESCAPE '\'
   OR LOWER(a.name)                 LIKE :q ESCAPE '\'
   OR EXISTS (SELECT 1 FROM transaction_categories tcx
                JOIN categories cx ON cx.id = tcx.category_id
               WHERE tcx.transaction_id = sub.id
                 AND LOWER(cx.name) LIKE :q ESCAPE '\')
)
```

Because search references `a.name`, it forces the **ACCOUNTS** join (LEFT JOIN, so account-less transactions still match on description/category).

---

## 6. Sort & pagination

- **Whitelist** sort fields → fixed outer columns: `date`→`sub.transaction_date`, `amount`→`sub.signed_amount`, `createdAt`→`sub.created_at`. **Reject any other field** with `400` (today's code passes unknown sort props straight into native SQL — fix this; it's a breakage + injection surface).
- Keep the **mandatory tie-breakers** for running-balance stability, appended after the user's sort: `sub.transaction_date DESC, sub.created_at DESC, sub.id DESC` (only add each if not already present). This is the existing logic in `getAllTransactions` — preserve it.
- Pagination: use `query.setFirstResult(page*size)` / `setMaxResults(size)` on the native query (Hibernate emits Oracle paging). `ORDER BY` stays in the SQL string (whitelisted columns only).

---

## 7. Refactor: extract `SqlPredicates` (shared)

Pull the predicate mechanics out of `TransactionQueryBuilder` into a new `@Component SqlPredicates` (place it in `com.financeos.domain.report.engine` next to `DateRangeResolver`, which it depends on). Move these **verbatim** (they're already pure except date):

- `numberPredicate`, `stringPredicate`, `enumPredicate`, `datePredicate` (needs injected `DateRangeResolver`), and a boolean case.
- `categoryPredicate` — **but parameterize the transaction-id reference**: add a `String txIdRef` arg so the report builder passes `"t.id"` and the list builder passes `"sub.id"`. (Today it hardcodes `t.id`.)
- static helpers `escapeLike`, `likeClause`, `textList`.

Expose a single dispatch entry point:

```java
// returns a bound predicate string, mutating params; null means "no constraint" (e.g. all_time)
String build(FieldType type, String expr, String op, JsonNode value, Map<String,Object> params, String p);
String category(String op, JsonNode value, Map<String,Object> params, String p, String txIdRef);
```

Then **`TransactionQueryBuilder.predicate(...)` delegates to `SqlPredicates`** (passing `"t.id"` for category) instead of containing the logic. **The existing report engine tests must stay green** — this is a behavior-preserving refactor; do not change report SQL output.

`SIGNED_AMOUNT` and the `escapeLike` constant remain reusable — the list builder may reference `TransactionQueryBuilder.SIGNED_AMOUNT` or you may relocate that constant into `SqlPredicates`. Pick one; keep a single definition.

---

## 8. SQL structure (exact)

The list builder produces a WHERE clause **once** (referencing the `sub` alias + optional `a` join) and reuses it in both the data and count queries.

**Inner subquery** (shared by both; exposes every column the outer WHERE/ORDER BY/search needs):

```sql
SELECT t.id, t.account_id, t.transaction_date, t.created_at, t.type, t.source,
       t.amount, t.description, t.sourced_description,
       t.is_under_monitoring, t.is_excluded, t.review_type,
       (CASE WHEN t.type = 'CREDIT' THEN t.amount ELSE -t.amount END) AS signed_amount
       /* DATA query only: */ ,
       (COALESCE(abd.opening_balance, 0) +
        SUM(CASE WHEN t.type = 'CREDIT' THEN t.amount ELSE -t.amount END)
            OVER (PARTITION BY t.account_id
                  ORDER BY t.transaction_date ASC, t.created_at ASC, t.id ASC)) AS balance
FROM transactions t
LEFT JOIN account_bank_details abd ON t.account_id = abd.account_id   -- DATA query only
WHERE t.user_id = :userId
```

**Data query:**

```sql
SELECT sub.id, sub.balance
FROM ( <inner, WITH balance> ) sub
LEFT JOIN accounts a ON a.id = sub.account_id    -- only if account/accountType/search used
WHERE <predicates>                               -- always at least "1=1" or just the AND-ed list
ORDER BY <whitelisted sub cols + tie-breakers>
-- setFirstResult / setMaxResults
```

**Count query** — same FROM/WHERE, **no window, no balance column, no abd join**:

```sql
SELECT count(*)
FROM ( <inner, WITHOUT balance> ) sub
LEFT JOIN accounts a ON a.id = sub.account_id    -- only if needed
WHERE <same predicates>
```

Both use the `sub` alias, so the WHERE string + params are built once and substituted into both. Each native query maps `Object[] {id, balance}` → the existing `TransactionBalanceProjection` (id → `UUID.fromString`, balance → `BigDecimal`); count maps to `long`.

Only add the `accounts a` join when a filter/sort/search actually references `account`, `accountType`, or `a.name` (track via a `Set<Join>` like the report builder does).

---

## 9. Files

**Create**
- `domain/report/engine/SqlPredicates.java` — shared predicate component (§7).
- `domain/transaction/TransactionListQueryBuilder.java` — owns the list field map (§4), builds the inner subquery, WHERE (filters + search), ORDER BY (whitelist + tie-breakers), and the count variant. Injects `SqlPredicates`, `DateRangeResolver` (or via `SqlPredicates`), and `DatasourceCatalog` (for `operatorsFor`).
- `domain/transaction/TransactionRepositoryCustom.java` + `TransactionRepositoryCustomImpl.java` — `@PersistenceContext EntityManager`; method e.g. `Page<TransactionBalanceProjection> findFiltered(UUID userId, TransactionSearchCriteria criteria, Pageable pageable)`. Builds SQL via the builder, runs data + count native queries, returns a `PageImpl`.
- `api/transaction/dto/TransactionSearchRequest.java` — `record(List<FilterClause> filters, String search)`.
- (optional) `domain/transaction/TransactionSearchCriteria.java` — normalized internal criteria passed builder→repo.

**Modify**
- `domain/report/engine/TransactionQueryBuilder.java` — delegate predicates to `SqlPredicates` (§7).
- `domain/transaction/TransactionRepository.java` — `extends JpaRepository<…>, TransactionRepositoryCustom`. Keep `findAllByIdIn` (phase 2). The static `findIdsWithRunningBalance` can be removed once the dynamic empty-filter path reaches parity — keep it until verified.
- `domain/transaction/TransactionService.java` — extract a shared private `queryTransactions(TransactionSearchCriteria, Pageable)` doing phase-1 (dynamic) → phase-2 hydration → balance map → `PageImpl`. `getAllTransactions` calls it with empty criteria; add `searchTransactions(TransactionSearchRequest, Pageable)`.
- `api/transaction/TransactionController.java` — add `@PostMapping("/search")` taking `@Valid @RequestBody TransactionSearchRequest` + `@PageableDefault(...)` `Pageable`.
- `api-spec.yaml` — add the `POST /api/v1/transactions/search` path, `TransactionSearchRequest` schema, and a `FilterClause` schema (reuse the report one if already defined).

---

## 10. Validation

- Unknown `field` → `400` (`ValidationException` / Bean Validation, matching how reports reject unknown fields).
- Operator not in `operatorsFor(fieldType)` → `400`.
- Malformed `value` for the operator (e.g. `between` without `{from,to}`) → `400`, not `500`.
- Unknown sort field → `400`.
- Enum value validation (e.g. a bogus `type`) is optional — an invalid value simply matches nothing — but a `400` is friendlier. Match the report engine's existing strictness; don't exceed it without asking.

---

## 11. Guardrails (do NOT)

- **Do NOT** push any filter/search into the inner window subquery (§2).
- **Do NOT** concatenate user values into SQL — every value is a bound parameter. Field/operator/sort names come only from the whitelists.
- **Do NOT** JOIN `transaction_categories` in the main FROM (fans rows out / breaks counts) — category filter & search use `EXISTS`.
- **Do NOT** change the `TransactionResponse` shape, the phase-2 hydration, or the tie-breaker sort semantics.
- **Do NOT** change report SQL output during the `SqlPredicates` refactor — report tests must stay green.
- **Do NOT** make `isExcluded` filter by default — the list shows everything unless explicitly filtered.

---

## 12. Tests

- **Balance correctness under filter (critical):** a transaction's `balance` in a filtered result equals its `balance` in the unfiltered ledger (filtering must not recompute balances).
- Filter by `type`, `amount` `between` (signed — negative range hits debits), `date` relative (`last_x_days`), `category` `in`, `accountId` `is`, `source` with **real** enum values, boolean flags.
- Search matches across description, sourcedDescription, account name, and category name (4 separate cases); blank search = no constraint.
- Sort whitelist: `amount`/`date`/`createdAt` work; an unlisted field → `400`. Tie-breakers keep order stable.
- Pagination: `totalElements` reflects the filtered count; last page partial; `page`/`size` correct.
- Tenancy: a user never sees another user's rows even with permissive filters.
- Validation: unknown field / bad operator / malformed value → `400`.
- Regression: existing report engine tests still pass after the refactor.

---

## 13. Milestones (stop for review after each)

- **M1 — Refactor only.** Extract `SqlPredicates`; make `TransactionQueryBuilder` delegate. No behavior change. Report tests green. Compile + boot.
- **M2 — List builder + repo fragment.** `TransactionListQueryBuilder` + custom repo; refactor `getAllTransactions` to the shared path with empty criteria. Verify `GET` output is byte-for-byte equivalent to today (balances, order, paging).
- **M3 — Search endpoint.** `POST /search`, DTO, controller, filters + search wired; validation; `api-spec.yaml`.
- **M4 — Tests & polish.** Full test suite (§12), remove the now-dead static `findIdsWithRunningBalance` if M2 reached parity.
