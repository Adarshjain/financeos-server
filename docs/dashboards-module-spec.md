# Dashboards Module — Design Specification

> Status: **Design locked (planning phase).** No implementation yet.
> Builds directly on the Reports module (`docs/reports-module-spec.md`).

## 1. Overview

A **dashboard** is a named, user-owned canvas that arranges several **report widgets** into a
grid. The dashboard holds no query logic of its own — each widget references a saved **report**
(KPI / Chart / Table), and rendering reuses the existing report engine to produce each widget's
data.

```
Dashboard (name, layout)
 └─ Widget[]  →  { reportId, layout {x,y,w,h}, optional title }
                  └─ references a saved report → run via POST /reports/{id}/data → KpiData/ChartData/TableData
```

The module is composition + layout + persistence; **all data generation is delegated to the
reports engine** that already exists.

### Conventions
- All endpoints under `/api/v1`. Multi-tenant via the existing Hibernate `userFilter`
  (`UserContext`), like every other entity.
- JSON-in-CLOB storage via hypersistence-utils + Oracle `IS JSON`, mirroring `reports.definition`.

## 2. Relationship to the existing `/api/v1/dashboard/summary`

The current `DashboardController` skeleton returns a **fixed** financial summary (net worth,
assets, liabilities, monthly income/expenses). That is a separate concern and **stays as-is** —
its figures (net worth/assets/liabilities) require account balances, which the report engine's
`transactions`-only datasource cannot yet produce. The new composable dashboards live at the
**plural** `/api/v1/dashboards`. (Build note: rename the existing class to
`DashboardSummaryController` to free `DashboardController` for the new CRUD; its path is unchanged.)

Once an accounts/positions datasource exists for reports, the fixed summary could be rebuilt as a
dashboard of KPI reports — out of scope for v1.

## 3. Data model

### Dashboard
| field | notes |
|---|---|
| `id` | UUID, server-assigned |
| `name` | required |
| `description` | optional |
| `widgets` | ordered array (see below), stored as JSON |
| `createdAt` / `updatedAt` | audit |

### Widget (element of `widgets[]`)
```jsonc
{
  "id": "w1",                 // client-generated, unique within the dashboard (manages grid state)
  "reportId": "<uuid>",       // the saved report this widget renders
  "title": null,              // optional display override; null = use the report's name
  "layout": { "x": 0, "y": 0, "w": 6, "h": 4 }
}
```
- **Layout** is a **12-column** grid: `x` in `0..11`, `w` in `1..12`, `x + w <= 12`, `y >= 0`,
  `h >= 1`. Overlap is not enforced server-side (the frontend grid resolves it). Responsive
  per-breakpoint layouts are deferred.
- The same report may appear in multiple widgets (distinct widget `id`s).

## 4. Storage

```sql
CREATE TABLE dashboards (
    id          VARCHAR2(36) PRIMARY KEY,
    user_id     VARCHAR2(36),
    name        VARCHAR2(255) NOT NULL,
    description VARCHAR2(4000),
    widgets     CLOB NOT NULL,   -- JSON array of widgets
    created_at  TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_dashboards_user FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT chk_dashboards_widgets_json CHECK (widgets IS JSON)
);
CREATE INDEX idx_dashboards_user_id ON dashboards(user_id);
```
(Next migration: `V13`.) Entity carries `@Filter(userFilter)`; `widgets` (de)serialized via Jackson
to a typed `DashboardWidget` record list. New code under `com.financeos.domain.dashboard` +
`com.financeos.api.dashboard`.

## 5. Endpoints

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/api/v1/dashboards` | Create a dashboard |
| `GET` | `/api/v1/dashboards` | List the user's dashboards (summaries) |
| `GET` | `/api/v1/dashboards/{id}` | Get one dashboard (widgets enriched with report metadata) |
| `PUT` | `/api/v1/dashboards/{id}` | Update (name, description, widgets) |
| `DELETE` | `/api/v1/dashboards/{id}` | Delete |

**No dashboard-level data endpoint in v1.** Widget data is fetched **per-widget** by the client via
the existing `POST /api/v1/reports/{id}/data` (and `?page=&size=` for table widgets). A batch
render endpoint can be added later if round-trips become a problem.

## 6. Request / response shapes

### Create / Update request
```jsonc
{
  "name": "My Finances",
  "description": "optional",
  "widgets": [
    { "id": "w1", "reportId": "<uuid>", "title": null, "layout": { "x": 0, "y": 0, "w": 6, "h": 4 } },
    { "id": "w2", "reportId": "<uuid>", "title": "Spend by Category", "layout": { "x": 6, "y": 0, "w": 6, "h": 4 } }
  ]
}
```
(Update replaces `name`, `description`, and the full `widgets` set.)

### Get response (widgets enriched, so the client can lay out + pick renderers without N calls)
```jsonc
{
  "id": "...",
  "name": "My Finances",
  "description": "optional",
  "widgets": [
    {
      "id": "w1",
      "reportId": "<uuid>",
      "title": null,
      "layout": { "x": 0, "y": 0, "w": 6, "h": 4 },
      "report": { "name": "Total Spend", "type": "KPI", "available": true }
    },
    {
      "id": "w2",
      "reportId": "<deleted-uuid>",
      "title": "Spend by Category",
      "layout": { "x": 6, "y": 0, "w": 6, "h": 4 },
      "report": { "name": null, "type": null, "available": false }   // graceful "unavailable"
    }
  ],
  "createdAt": "...",
  "updatedAt": "..."
}
```
The `report` block is resolved server-side by batch-loading referenced reports **scoped to the
current user** (so another user's report id, or a deleted one, resolves to `available: false`).
The client uses `report.type` to choose the KPI/Chart/Table renderer, then fetches data per widget.

### List response (summaries)
```jsonc
[
  { "id": "...", "name": "My Finances", "description": "optional", "widgetCount": 5, "createdAt": "...", "updatedAt": "..." }
]
```

## 7. Validation (on create / update)

- `name` required.
- Each widget: `id` present and **unique within the dashboard**; `reportId` present; `layout`
  within grid bounds (`x` 0..11, `w` 1..12, `x+w<=12`, `y>=0`, `h>=1`).
- **No save-time check that `reportId` exists** — references resolve (and are ownership-checked)
  at read/render time → graceful `available: false`. This means editing a dashboard is never
  blocked by an unrelated report having been deleted. Security holds because read-enrichment is
  user-scoped and `POST /reports/{id}/data` enforces ownership, so a stale/foreign id leaks nothing.

## 8. Rendering flow (client)

1. `GET /api/v1/dashboards/{id}` → dashboard + widgets (each with `layout`, `report.type`,
   `report.available`).
2. Lay widgets onto the 12-col grid; for each `available` widget, `POST /reports/{reportId}/data`
   (with `page`/`size` for table widgets) and render by `report.type`.
3. Unavailable widgets render a placeholder (e.g. "report no longer exists").

## 9. Decisions

**Locked:** widgets reference saved reports (deleted → graceful unavailable); 12-column grid with
per-widget `{x,y,w,h}`; per-widget data fetch via the existing reports endpoints; JSON-in-CLOB
storage; new module at `/api/v1/dashboards`, fixed `dashboard/summary` left as-is.

**Deferred (post-v1):** dashboard-level global filters (shared date range / account control across
all widgets); batch render endpoint; non-report widgets (text/markdown, dividers); responsive
per-breakpoint layouts; rebuilding the fixed summary as a dashboard once an accounts datasource
exists.
