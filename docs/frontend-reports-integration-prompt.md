# Frontend task — Reports module API client layer

> Paste this as the task prompt in the **frontend** repo. Attach `api-spec.yaml` (the
> backend OpenAPI spec) alongside it. This document is authoritative for the report
> **definition** and **data** shapes and for behavioural decisions; `api-spec.yaml` is
> authoritative for endpoints, HTTP methods, query params, and auth.

---

## Your task

Build the **API client / data layer** for a new **Reports** feature — typed request/response
models and the methods that call the backend endpoints. **Do NOT build any UI** yet
(no components, pages, hooks-into-views, styling). The goal is a clean, typed, well-documented
client surface I can wire into the UI later, once the UI is ready. I'll tell you when to wire it.

### Step 0 — match the existing codebase (do this first)
Before writing anything, explore this repo and **follow its established conventions exactly**:
- How are existing API modules structured (e.g. a `transactions`/`accounts` service or client)?
  Mirror that structure, file layout, and naming for a new `reports` module.
- What HTTP client is used (fetch wrapper, axios, react-query, RTK Query, etc.)? Use the same.
- Where do shared types live, and what's the type style (TS interfaces vs types, enums vs unions)?
- How is auth handled? These endpoints use the **same session/cookie auth** as every other
  endpoint — reuse the existing authenticated client; do not add new auth.
- How are errors surfaced (the backend returns `400` with an `ErrorResponse` body for validation
  failures, `401` when unauthenticated)? Handle them the same way existing modules do.

Produce code that looks like it was written by whoever wrote the rest of the API layer.

---

## The feature in one paragraph

Reports are **dynamic**: the client first fetches a **datasource catalog** of reportable fields
and operators, uses it to let a user assemble a **report definition** of one of three types
(**KPI**, **CHART**, **TABLE** — mutually exclusive, one type per report), then **saves** and/or
**runs** that definition to get back computed **data** to render. There are two run modes: run a
**saved** report by id, or run an **ad-hoc** (unsaved) definition for live preview.

---

## Endpoints to implement (see `api-spec.yaml` for full detail)

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/api/v1/report/datasource` | Field + operator catalog (note: singular `report`) |
| `POST` | `/api/v1/reports` | Create & save a report → `ReportResponse` |
| `GET` | `/api/v1/reports?type=KPI` | List the user's reports (metadata); `type` optional |
| `GET` | `/api/v1/reports/{id}` | Get one report definition |
| `PUT` | `/api/v1/reports/{id}` | Update (`name` + `definition`) |
| `DELETE` | `/api/v1/reports/{id}` | Delete |
| `POST` | `/api/v1/reports/{id}/data` | Run a **saved** report → `ReportData` (`?page=&size=` for tables) |
| `POST` | `/api/v1/reports/data` | Run an **ad-hoc** definition → `ReportData` (`?page=&size=` for tables) |

Suggested method names (adapt to repo style): `getReportDatasource()`, `createReport(body)`,
`listReports(type?)`, `getReport(id)`, `updateReport(id, body)`, `deleteReport(id)`,
`runSavedReport(id, { page?, size? })`, `runAdHocReport(body, { page?, size? })`.

---

## Types you must model

### 1. Datasource catalog — `GET /report/datasource` response
```ts
type FieldType = 'number' | 'date' | 'string' | 'enum' | 'boolean';
type FieldRole = 'measure' | 'dimension' | 'filter';
type Aggregation = 'sum' | 'avg' | 'count' | 'min' | 'max';
type ReportType = 'KPI' | 'CHART' | 'TABLE';   // UPPERCASE everywhere

interface FieldDefinition {
  name: string;
  label: string;
  type: FieldType;
  role: FieldRole;
  aggregations?: Aggregation[];   // present for measures only
  values?: string[];              // present for STATIC enums only
  dynamic?: boolean;              // true for user-specific enums (category, account)
  allowedInReports: ReportType[]; // which report types may DISPLAY/GROUP-BY this field
}

interface DatasourceCatalog {
  fields: FieldDefinition[];
  operators: {
    date: { absolute: string[]; relative: string[] };
    string: string[];
    number: string[];
    enum: string[];
    boolean: string[];
  };
}
```

### 2. Report definition — a discriminated union on `type`
A definition is the type-specific config. Model it as a union keyed on the report `type`. The
fields below are the contract (the backend stores this verbatim as JSON):

```ts
// Shared
interface FilterClause {
  field: string;        // a field name from the catalog
  operator: string;     // an operator valid for that field's type
  value?: FilterValue;  // shape depends on operator (see table below); omit for valueless ops
}
type SortClause = { key: string; direction: 'asc' | 'desc' };
type DimensionRef = { field: string; granularity?: Granularity }; // granularity REQUIRED iff field is a date
type Granularity = 'day' | 'week' | 'month' | 'quarter' | 'year';
type MeasureRef = { field: string; aggregation: Aggregation };

interface KpiDefinition {
  measure: string;                 // a MEASURE field (e.g. "amount")
  aggregation: Aggregation;
  includeExcluded: boolean;        // REQUIRED — always send it (see decisions)
  filters: FilterClause[];
  comparison?: { enabled: boolean; period: 'previous_period' };
}

interface ChartDefinition {
  chartType: 'line' | 'bar' | 'stackedBar' | 'area' | 'pie' | 'donut';
  dimension: DimensionRef;         // x-axis / primary grouping
  series?: DimensionRef;           // optional split into multiple series (ignored for pie/donut)
  measure: MeasureRef;             // single measure (v1)
  includeExcluded: boolean;        // REQUIRED
  filters: FilterClause[];
  // NOTE: no sort, no limit — charts are not sorted server-side; cap series/points client-side.
}

interface TableDefinitionRaw {
  mode: 'raw';
  columns: string[];               // field names; order = column order
  includeExcluded: boolean;        // REQUIRED
  filters: FilterClause[];
  sort?: SortClause[];             // keys are column (field) names
  pageSize?: number;               // default page size (current page is a runtime param)
}
interface TableDefinitionAggregated {
  mode: 'aggregated';
  groupBy: DimensionRef[];         // 1+ dimensions
  measures: MeasureRef[];          // 1+ measures
  includeExcluded: boolean;        // REQUIRED
  filters: FilterClause[];
  sort?: SortClause[];             // keys: a groupBy field name OR an aggregated column key `${field}_${aggregation}`
  pageSize?: number;
}
type TableDefinition = TableDefinitionRaw | TableDefinitionAggregated;
type ReportDefinition = KpiDefinition | ChartDefinition | TableDefinition;
```

### 3. Filter `value` shapes (by operator) — get this exactly right
```ts
type FilterValue =
  | string | number | boolean         // scalar ops: is, is_not, equals, greater_than, less_than,
                                       //   after, before, exact, starts_with, ends_with, contains
  | string[]                          // in / not_in  (enum & string)
  | { from: string; to: string }      // date 'between'  (ISO yyyy-MM-dd strings)
  | { from: number; to: number }      // number 'between'
  | { amount: number };               // relative date with a parameter: last_x_days / last_x_months / last_x_years
// VALUELESS relative date operators (omit `value` entirely):
//   this_month, this_week, this_year, previous_month, previous_week, previous_year,
//   today, yesterday, current_fy, prev_fy, all_time
```
Filters in the array are **AND-ed** together. **Any** catalog field can be filtered, regardless of
its `allowedInReports` — `allowedInReports` only governs whether a field can be a measure /
dimension / column, not whether it can be a filter.

### 4. Requests / responses
```ts
interface CreateReportRequest { name: string; type: ReportType; datasource: string; definition: ReportDefinition; }
interface UpdateReportRequest { name: string; definition: ReportDefinition; }   // type & datasource immutable
interface RunReportRequest    { type: ReportType; datasource: string; definition: ReportDefinition; }
interface ReportResponse        { id: string; name: string; type: ReportType; datasource: string; definition: ReportDefinition; createdAt: string; updatedAt: string; }
interface ReportSummaryResponse { id: string; name: string; type: ReportType; datasource: string; createdAt: string; updatedAt: string; }
```

### 5. Report DATA — discriminated union on `type`
```ts
interface KpiData {
  type: 'KPI';
  value: number | null;
  measure: string;
  aggregation: Aggregation;
  comparison: { previousValue: number; change: number; changePercent: number | null; direction: 'up' | 'down' | 'flat' } | null;
  meta: { rowCount: number; dateRange: { from: string; to: string } | null };
}
interface ChartData {
  type: 'CHART';
  chartType: string;
  dimension: string;
  categories: string[];                                   // x-axis labels, ordered
  series: { name: string; data: (number | null)[] }[];    // data[] aligned by index to categories
  measure: { field: string; aggregation: Aggregation };
  meta: { rowCount: number; dateRange: { from: string; to: string } | null };
}
interface TableData {
  type: 'TABLE';
  mode: 'raw' | 'aggregated';
  columns: { key: string; label: string; type: string }[];
  rows: Record<string, unknown>[];   // keyed by column.key; raw rows also include an `id` (transaction id)
  page: { number: number; size: number; totalElements: number; totalPages: number };
}
type ReportData = KpiData | ChartData | TableData;
```

---

## Decisions you MUST honour (these affect correctness)

1. **Casing is UPPERCASE** for report types everywhere: `KPI` / `CHART` / `TABLE` — in the `type`
   field, in `allowedInReports`, and in the `?type=` query param. Don't title-case.
2. **`amount` is SIGNED** at the API: credits are positive, debits are negative. There is no separate
   "net" field. `SUM(amount)` over everything = net cashflow; a "total spend" KPI is `amount/sum`
   filtered to `type = DEBIT`, which returns a **negative** number — display it as the API returns it.
3. **`includeExcluded` is REQUIRED on every definition** and the backend applies no default. Always
   send it. Default the UI toggle to `false` (exclude transactions flagged "excluded from reports").
4. **Field roles drive the builder**: `measure` → can be aggregated (KPI value, chart/table measure);
   `dimension` → can be grouped / axis / column; `filter` → filter-only (never displayed). Only show a
   field as a measure/dimension/column where its `allowedInReports` includes the current report type.
5. **Aggregations** come from each measure field's `aggregations` list. Don't offer aggregations a
   field doesn't list.
6. **Enum field values**:
   - Static enums (e.g. `type`, `source`, `accountType`) ship their `values` inline in the catalog.
   - **Dynamic enums** (`category`, `account`) have `dynamic: true` and **no** `values` — fetch their
     options from the existing endpoints (`GET /api/v1/categories`, `GET /api/v1/accounts`) and filter
     by **name** (the dimension/filter value is the category/account name string).
7. **Operators** are per field type (`operators` map). Build the operator dropdown from the field's
   `type`. For `date`, the catalog splits operators into `absolute` and `relative` — render a date
   picker for absolute ops and a preset list for relative ops; serialize `value` per the table above.
8. **KPI comparison** is **on by default**. To turn it off, send `comparison: { enabled: false }`.
   It is automatically null in the response when the date range is `all_time` or there is no bounded
   date filter. `change`/`changePercent`/`direction` are computed on the **signed** value, so a spend
   KPI growing more negative reports `direction: "down"`. If you want "spend went up" framing in the
   UI, derive it yourself from the measure's sign.
9. **Date / fiscal year**: relative ranges resolve server-side; `current_fy`/`prev_fy` use an
   **April–March** fiscal year. `meta.dateRange` in the response echoes the resolved window — use it
   for labels instead of recomputing.
10. **Chart**: a date `dimension`/`series` **requires** a `granularity`; a non-date dimension must NOT
    send granularity. One measure + optional one series in v1. `pie`/`donut` ignore `series`
    (single-dimension slices). No server-side sort; cap points/series client-side if needed. Response
    is charting-library-friendly: `categories[]` + `series[{name,data[]}]` aligned by index.
11. **Table**: `mode` is `raw` or `aggregated`. Aggregated column keys follow `${field}_${aggregation}`
    (e.g. `amount_sum`); `sort.key` references either a groupBy field name or such a measure key. `sort`
    and `pageSize` live in the **definition** (defaults); the **current page** is a **runtime query
    param** (`?page=&size=`) on the data call, NOT part of the saved definition. Raw rows always carry
    a hidden `id` (the transaction id) for drill-through.
12. **Multi-valued category caveat (v1)**: a transaction can have multiple categories. When you GROUP
    BY / split a chart or aggregated table on `category`, a transaction is counted once **per** category
    it carries, so per-category sums can exceed the overall total. (Category *filters* do not double
    count.) If the UI shows category breakdowns, consider a small note; don't try to "fix" it client-side.
13. **Two run modes**: `POST /reports/{id}/data` runs a saved report; `POST /reports/data` runs an
    unsaved definition (use this for live preview while the user is building, before they save).

---

## Deliverables

- A typed `reports` API module (types + methods above) consistent with the existing API layer.
- The TypeScript (or repo-language) models for the catalog, the discriminated `ReportDefinition` and
  `ReportData` unions, and the request/response DTOs.
- Optional small, pure helpers if they fit the repo's style (e.g. a builder for `FilterClause`
  values, an `isKpiData`/`isChartData`/`isTableData` narrowing helper). Keep them minimal.
- Brief usage notes / JSDoc on each method.

## Do NOT
- Build UI components, pages, routes, charts, or styling.
- Wire anything into screens — I'll do that and tell you when.
- Invent endpoints, fields, operators, or report types beyond what's in `api-spec.yaml` and this doc.
- Change the casing or the `includeExcluded`/signed-amount/granularity rules above.
