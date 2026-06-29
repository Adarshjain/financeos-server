# Frontend task — Reports module CHANGES (update client + UI)

> Paste this in the **frontend** repo. Attach the updated `api-spec.yaml`. This is a **delta** on
> the existing reports client/UI — update the affected types, API calls, **and UI** to match the
> seven backend changes below. (You already have reports UI; adjust it, don't rebuild from scratch.)

There are **breaking changes** (removed `includeExcluded`, removed table `pageSize`, and the
aggregated-table request *and* response shapes changed). Update types first, then callers, then UI.

---

## 1. KPI: "good vs bad" sentiment

- **Definition** (KPI) gains an optional `comparison.higherIsBetter: boolean`.
- **Response** `KpiData.comparison` gains `sentiment: "good" | "bad" | "neutral"`.

Server logic: an increase is `good` when `higherIsBetter` is true (and `bad` when false); a
decrease is the inverse; `neutral` when `higherIsBetter` is unset or change is zero.

**Do:** add a "higher is better?" control to the KPI builder (writes `comparison.higherIsBetter`).
In the KPI tile, **color the delta from `sentiment`** (good = positive/green, bad = negative/red,
neutral = muted) instead of inferring good/bad from `direction`. `direction` (`up/down/flat`) still
exists for the arrow; `sentiment` is the value judgement.

## 2. KPI: actual previous period

`KpiData.comparison` now includes **`previousDateRange: { from, to }`** alongside `previousValue`.
Show the compared window (e.g. tooltip "vs 1 May–31 May: ₹-38,900"). It's null when the range is
unbounded.

## 3. `includeExcluded` removed → it's now a filter field (BREAKING)

- **Remove `includeExcluded`** from every report definition (KPI, Chart, Table) — it no longer exists.
- The datasource catalog now returns a boolean **filter** field **`isExcluded`** (role `filter`).
- To hide excluded transactions, add a normal filter clause:
  `{ "field": "isExcluded", "operator": "is", "value": false }`.

**Do:** convert the old "include excluded" toggle into managing this filter clause — **default it ON**
(i.e. default to sending `isExcluded is false`); when the user turns it off, drop the clause (include
everything). The backend applies no default, so if you send no `isExcluded` filter, excluded txns are
included.

## 4. Table: page size is runtime (BREAKING)

- **Remove `pageSize`** from table report definitions (raw and aggregated).
- Page size is a **runtime query param**: `runSavedReport(id, { page, size })` /
  `runAdHocReport(body, { page, size })`. Server default is 50, max 1000.

**Do:** drive `size` from the table component's page-size control, not the saved definition. `sort`
stays in the definition.

## 5. Date labels are pre-formatted per granularity

Chart `categories`, and pivot row/column header values for date dimensions, now arrive
**display-formatted** from the server:

| Granularity | Label |
|---|---|
| day | `15 Jun 26` |
| week | `W12 26` (ISO week) |
| month | `Jun 26` |
| quarter | `Q3 26` |
| year | `2026` |

**Do:** render these labels as-is — **don't reformat or parse them as dates**. Series/axis order is
already chronological (preserve array order; don't re-sort alphabetically).

## 6. Aggregated tables are now PIVOTS (BREAKING — request + response)

The flat aggregated table is replaced by a pivot. **Raw tables are unchanged.**

**Definition — aggregated** (replaces the old `groupBy`):
```ts
{
  mode: "aggregated",
  rows:    DimensionRef[],   // 1+ row-group dimensions
  columns: DimensionRef[],   // 0+ column-group dimensions (omit/empty = flat rows × measures)
  measures: MeasureRef[],    // 1+
  filters: FilterClause[],
  sort?: SortClause[]        // keys: a row dimension field, or a measure key (only when columns is empty)
}
// Raw stays: { mode: "raw", columns: string[], filters, sort? }
```

**Response — `PivotTableData`** (aggregated tables now return this, NOT the flat `TableData`):
```ts
interface PivotTableData {
  type: "TABLE";
  mode: "aggregated";
  rowDimensions:    { field: string; label: string }[];
  columnDimensions: { field: string; label: string }[];
  measures: { key: string; field: string; aggregation: string; label: string }[]; // key = `${field}_${aggregation}`
  columns: { key: string; values: Record<string,string> }[];   // distinct column combos; key "" when no column dims
  rows: {                                                        // the requested page of row groups
    key: string;
    values: Record<string,string>;                              // rowDimField -> formatted value
    cells: Record<string /*columnKey*/, Record<string /*measureKey*/, number>>;
  }[];
  page: { number: number; size: number; totalElements: number; totalPages: number };
}
// Raw tables still return the flat TableData { type, mode:"raw", columns[], rows[], page }.
```

**Do:**
- Update the aggregated-table **builder UI**: separate **Rows** and **Columns** dimension pickers
  (plus measures), instead of one flat group-by list. A field can't be in both rows and columns.
- Update the **renderer**: render a pivot matrix — `rowDimensions` down the left, `columns × measures`
  across the top, body cell = `rows[i].cells[columnKey][measureKey]` (missing → blank/0). When
  `columnDimensions` is empty there's a single column with key `""` → render a plain rows×measures table.
- Add **`PivotTableData`** to the `ReportData` union and branch your table renderer on
  `mode` (`"raw"` → existing flat `TableData`; `"aggregated"` → `PivotTableData`).
- `totalElements`/pagination count **row groups**.

## 7. Reports have descriptions

`CreateReportRequest`, `UpdateReportRequest`, `ReportResponse`, and `ReportSummaryResponse` gain an
optional **`description`** string. Add a description field to the report editor and show it where you
list reports.

---

## Summary of type changes
- `KpiDefinition`: remove `includeExcluded`; `comparison` gains `higherIsBetter?`.
- `KpiData.comparison`: add `previousDateRange`, `sentiment`.
- `ChartDefinition`: remove `includeExcluded`.
- Table definition: split into raw `{mode:"raw", columns:string[], …}` and aggregated
  `{mode:"aggregated", rows, columns, measures, …}`; remove `includeExcluded` and `pageSize` from both.
- New response type `PivotTableData`; add to `ReportData` union.
- Datasource catalog: new `isExcluded` boolean filter field.
- Report request/response types: add `description`.

Authoritative shapes are in the updated `api-spec.yaml` (`KpiData`, `PivotTableData`, `TableData`,
`CreateReportRequest`, `ReportResponse`, …). Reuse the existing auth/HTTP client; report types remain
UPPERCASE (`KPI`/`CHART`/`TABLE`).
