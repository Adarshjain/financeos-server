# Reports Module — Design Specification

> Status: **Design locked (planning phase).** No implementation yet.
> Scope: dynamic, user-defined reports over the `transactions` data source.

## 1. Overview

The Reports module lets a client build **dynamic reports** from the fields available in the
data model. The flow:

1. Client calls `GET /api/v1/report/datasource` → receives the catalog of reportable
   **fields** and the **operators** available per field type.
2. The GUI uses that catalog to build a **report definition** of one of three types —
   **KPI**, **Chart**, or **Table** (one type per report; never mixed).
3. The definition is saved (`POST /api/v1/reports`) and/or executed
   (`POST /api/v1/reports/{id}/data` for saved, `POST /api/v1/reports/data` for ad-hoc).
4. The engine returns computed data in a type-specific shape; the client renders it.

### Conventions
- All endpoints under `/api/v1`. Multi-tenant: every query is auto-scoped to the session
  user via the existing Hibernate `userFilter` (`UserContext`).
- **`amount` is signed at the API boundary** (DEBIT negated, CREDIT positive), matching
  `TransactionResponse.from` and the running-balance SQL
  `SUM(CASE WHEN type='CREDIT' THEN amount ELSE -amount END)`. There is **no** separate
  `netAmount` field. `SUM(amount)` = net cashflow; "total spend" = `SUM(amount)` filtered to
  `type = DEBIT` (a negative number, consistent with how the client already sees debits).

---

## 2. Data source — `GET /api/v1/report/datasource`

Single data source for v1: **`transactions`** (joined with `accounts` and `categories`).
Returns `{ fields, operators }`.

### 2.1 Fields

| name | label | type | role | aggregations | allowedInReports | notes |
|---|---|---|---|---|---|---|
| `amount` | Amount | number | measure | sum, avg, count, min, max | KPI, Chart, Table | signed |
| `date` | Date | date | dimension | — | Chart, Table | filterable everywhere (incl. KPI) |
| `type` | Type | enum | dimension | — | Chart, Table | values: `DEBIT`, `CREDIT` |
| `category` | Category | enum (dynamic) | dimension | — | Chart, Table | per-user; GUI fetches values from `/categories` |
| `account` | Account | enum (dynamic) | dimension | — | Chart, Table | per-user; GUI fetches values from `/accounts` |
| `accountType` | Account Type | enum | dimension | — | Chart, Table | values: `bank_account`, `credit_card`, `stock`, `mutual_fund`, `generic` |
| `source` | Source | enum | dimension | — | Chart, Table | values: `gmail`, `manual` |
| `description` | Description | string | dimension | — | Table | Table-only (free text, high cardinality); filterable elsewhere |
| `isUnderMonitoring` | Under monitoring | boolean | filter | — | (none) | filter only |

> `is_excluded` is **not** a generic filter field. It is controlled by the dedicated,
> required `includeExcluded` flag in every report definition (see §3.2).

Field attributes:
- `role`: `measure` | `dimension` | `filter`.
- `aggregations`: present on measures only.
- `values`: inline for static enums; omitted for `dynamic: true` enums (`category`, `account`).
- `allowedInReports`: which report types may *display/group by* the field. (Filters apply
  regardless of this list.)

### 2.2 Operators (by field type)

```jsonc
{
  "date": {
    "absolute": ["is", "after", "before", "between"],
    "relative": ["this_month", "this_week", "this_year", "previous_month",
                 "previous_week", "previous_year", "last_x_days", "last_x_months",
                 "last_x_years", "today", "yesterday", "current_fy", "prev_fy", "all_time"]
  },
  "string":  ["exact", "starts_with", "ends_with", "contains", "in"],
  "number":  ["equals", "greater_than", "less_than", "between"],
  "enum":    ["is", "is_not", "in", "not_in"],
  "boolean": ["is"]
}
```

---

## 3. Common definition concepts

### 3.1 Envelope (all report types)

```jsonc
{
  "id": "…",                 // server-assigned
  "name": "Total Spend",
  "type": "KPI",             // KPI | CHART | TABLE
  "datasource": "transactions",
  "definition": { /* type-specific, see §4–6 */ },
  "createdAt": "…",
  "updatedAt": "…"
}
```

### 3.2 `includeExcluded` (required, every definition)
Boolean. The backend applies **no silent default** — the client always sends it.
`false` → transactions flagged `is_excluded` are kept out (GUI default). `true` → included.

### 3.3 Filters
Flat list, **AND-ed** together (no nested AND/OR groups in v1). Each filter:
`{ "field", "operator", "value" }`. Value shapes:

| Operator kind | Example |
|---|---|
| enum `is`/`is_not` | `{ "field": "type", "operator": "is", "value": "DEBIT" }` |
| enum/string `in`/`not_in` | `{ "field": "category", "operator": "in", "value": ["Food","Travel"] }` |
| number `equals`/`greater_than`/`less_than` | `{ "field": "amount", "operator": "greater_than", "value": 1000 }` |
| number/date `between` | `{ "field": "date", "operator": "between", "value": { "from": "2026-01-01", "to": "2026-03-31" } }` |
| date relative (parameterized) | `{ "field": "date", "operator": "last_x_days", "value": { "amount": 30 } }` |
| date relative (no value) | `{ "field": "date", "operator": "this_month" }` |
| string `contains`/`exact`/… | `{ "field": "description", "operator": "contains", "value": "swiggy" }` |
| boolean `is` | `{ "field": "isUnderMonitoring", "operator": "is", "value": true }` |

---

## 4. KPI report

A single aggregated number, with period-over-period comparison.

### Definition
```jsonc
{
  "measure": "amount",
  "aggregation": "sum",                                       // from the field's allowed aggregations
  "includeExcluded": false,                                   // required
  "filters": [
    { "field": "type", "operator": "is", "value": "DEBIT" },
    { "field": "date", "operator": "this_month" }             // or "all_time"
  ],
  "comparison": { "enabled": true, "period": "previous_period" }   // enabled:false to opt out
}
```

### Data response — `POST /api/v1/reports/{id}/data`
```jsonc
{
  "type": "KPI",
  "value": -45230.50,
  "measure": "amount",
  "aggregation": "sum",
  "comparison": {                 // null when disabled OR range is all_time
    "previousValue": -38900.00,
    "change": -6330.50,           // value - previousValue (signed)
    "changePercent": -16.27,      // change / |previousValue| * 100 (signed)
    "direction": "down"           // sign of change: up | down | flat
  },
  "meta": {
    "rowCount": 128,
    "dateRange": { "from": "2026-06-01", "to": "2026-06-30" }   // resolved window
  }
}
```

- `comparison.enabled` defaults to **on**. `previous_period` = the equal-length window
  immediately preceding the resolved range (for calendar-named ranges like `this_month` /
  `current_fy`, the previous calendar unit). Null when disabled or range is `all_time`.
- `change`, `changePercent`, and `direction` are all computed on the **signed** value
  (`value - previousValue`). For a spend KPI (negative values) that grows in magnitude,
  the signed value falls, so `direction` is `"down"` — it tracks the signed metric, not
  spend magnitude. Clients that want "spend went up" framing can derive it from the sign
  of the underlying measure.

---

## 5. Chart report

An aggregated measure across a dimension, optionally split into series.

### Definition
```jsonc
{
  "chartType": "bar",            // line | bar | stackedBar | area | pie | donut
  "dimension": {                 // X-axis / primary grouping
    "field": "date",
    "granularity": "month"       // required for date: day|week|month|quarter|year
  },
  "series": { "field": "category" },                          // OPTIONAL: split into series/stacks
  "measure": { "field": "amount", "aggregation": "sum" },     // single measure (v1)
  "includeExcluded": false,
  "filters": [
    { "field": "type", "operator": "is", "value": "DEBIT" },
    { "field": "date", "operator": "current_fy" }
  ]
}
```
- One measure + optional one series dimension (v1). No `sort` (N/A for charts), no `limit`
  (client-side), no `comparison`.

### Data response (charting-library shape)
```jsonc
{
  "type": "CHART",
  "chartType": "bar",
  "dimension": "date",
  "categories": ["2026-04", "2026-05", "2026-06"],   // X-axis buckets, ordered
  "series": [
    { "name": "Food",   "data": [-1200, -1500, -1100] },
    { "name": "Travel", "data": [-800,  -300,  -2000] }
  ],
  "measure": { "field": "amount", "aggregation": "sum" },
  "meta": { "rowCount": 540, "dateRange": { "from": "2026-04-01", "to": "2027-03-31" } }
}
```
- No series dimension → a single series named after the measure; `data[]` aligned to
  `categories`. Missing buckets → `0`/`null`.
- Pie / donut → `categories` are slice labels (from `dimension`); `series` (split) ignored.

---

## 6. Table report

### 6.1 Raw mode (column-selected transaction list)
```jsonc
{
  "mode": "raw",
  "columns": ["date", "description", "amount", "category", "account", "type"],  // order = column order
  "includeExcluded": false,
  "filters": [
    { "field": "type", "operator": "is", "value": "DEBIT" },
    { "field": "amount", "operator": "less_than", "value": -5000 }
  ],
  "sort": [ { "key": "date", "direction": "desc" } ],   // multi-column allowed
  "pageSize": 50
}
```

### 6.2 Aggregated mode (GROUP BY summary)
```jsonc
{
  "mode": "aggregated",
  "groupBy": [
    { "field": "date", "granularity": "month" },        // granularity required for date
    { "field": "category" }
  ],
  "measures": [
    { "field": "amount", "aggregation": "sum" },
    { "field": "amount", "aggregation": "count" }        // multiple measures OK
  ],
  "includeExcluded": false,
  "filters": [ { "field": "type", "operator": "is", "value": "DEBIT" } ],
  "sort": [ { "key": "amount_sum", "direction": "desc" } ],
  "pageSize": 50
}
```

**Column-key convention** (used in `sort` and the response): group columns = field name
(`category`, `date`); aggregated columns = `{field}_{aggregation}` (`amount_sum`, `amount_count`).

### 6.3 Data response — generic columns + rows
```jsonc
{
  "type": "TABLE",
  "mode": "aggregated",
  "columns": [
    { "key": "date",         "label": "Month",        "type": "date" },
    { "key": "category",     "label": "Category",     "type": "string" },
    { "key": "amount_sum",   "label": "Amount (Sum)", "type": "number" },
    { "key": "amount_count", "label": "Count",        "type": "number" }
  ],
  "rows": [
    { "date": "2026-06", "category": "Food",   "amount_sum": -12500.00, "amount_count": 48 },
    { "date": "2026-06", "category": "Travel", "amount_sum": -8300.00,  "amount_count": 12 }
  ],
  "page": { "number": 0, "size": 50, "totalElements": 87, "totalPages": 2 }
}
```
- **Raw** mode: `columns` are the selected fields; each row also carries the transaction
  `id` (for drill-through / row actions) even if not a visible column.
- `sort` + `pageSize` live in the definition (defaults); the **current page** is a runtime
  query param (`?page=2`), sort may be overridden at runtime.

---

## 7. Storage

Single polymorphic table; `definition` stored as JSON (hypersistence-utils + Oracle
`IS JSON`, mirroring `transactions.metadata`). Entity carries `@Filter(userFilter)`.
`definition` deserializes to typed records (`KpiDefinition` / `ChartDefinition` /
`TableDefinition`) keyed on `type`; validated against the datasource catalog on write.

```sql
CREATE TABLE reports (
    id          VARCHAR2(36) PRIMARY KEY,
    user_id     VARCHAR2(36),
    name        VARCHAR2(255) NOT NULL,
    type        VARCHAR2(20)  NOT NULL,   -- KPI | CHART | TABLE
    datasource  VARCHAR2(50)  NOT NULL,   -- 'transactions'
    definition  CLOB          NOT NULL,   -- type-specific block, JSON
    created_at  TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_reports_user FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT chk_reports_type CHECK (type IN ('KPI','CHART','TABLE')),
    CONSTRAINT chk_reports_definition_json CHECK (definition IS JSON)
);
CREATE INDEX idx_reports_user_id ON reports(user_id);
```

---

## 8. Endpoints

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/api/v1/report/datasource` | Field + operator catalog |
| `POST` | `/api/v1/reports` | Create & save (validates definition) |
| `GET` | `/api/v1/reports` | List user's reports (`?type=` optional) |
| `GET` | `/api/v1/reports/{id}` | Fetch one definition |
| `PUT` | `/api/v1/reports/{id}` | Update |
| `DELETE` | `/api/v1/reports/{id}` | Delete |
| `POST` | `/api/v1/reports/{id}/data` | Run a saved report (`?page=`/`?size=` for tables) |
| `POST` | `/api/v1/reports/data` | Run an ad-hoc definition (unsaved quick-view) |

---

## 9. Engine semantics

- **Signed amount:** aggregations operate on the signed value
  (`CREDIT +`, `DEBIT −`), consistent with the transactions API and running-balance SQL.
- **Date resolution:** relative operators resolve to a concrete `[from, to]` at query time;
  `meta.dateRange` echoes the resolved window. `current_fy` / `prev_fy` use the configured
  financial-year boundary.
- **`previous_period`:** equal-length window immediately preceding the resolved range
  (calendar-aligned for named ranges).
- **Granularity:** date buckets for charts and aggregated tables (day/week/month/quarter/year).

---

## 10. Deferred (post-v1)

- **Multi-valued category double-count:** grouping/splitting by `category` counts a
  transaction once per category it carries, so per-category sums can exceed the overall
  total. Accepted for v1; revisit (primary-category or dedup rule) later.
- Additional report types: **Pivot/Matrix**, gauge/goal-progress.
- Multiple measures per chart; nested AND/OR filter groups; additional data sources
  (investments, accounts).
