# Frontend task — Dashboards module API client layer

> Paste this as the task prompt in the **frontend** repo. Attach `api-spec.yaml`. This builds on
> the Reports client (`docs/frontend-reports-integration-prompt.md`) — dashboards **reuse** that
> client to fetch each widget's data, so do the reports client first (or alongside).

---

## Your task

Build the **API client / data layer** for the **Dashboards** feature — typed models and the
methods that call the backend endpoints. **Do NOT build UI** yet (no grid editor, no widget
rendering, no styling). The goal is a clean, typed client surface I can wire into the UI later.

### Step 0 — match the existing codebase (do this first)
Mirror the conventions already in the repo (and the ones you just used for the reports client):
HTTP client, type style, file layout for an API module, session/cookie auth (these endpoints use
the **same** auth as everything else), and error handling (`400` → `ErrorResponse`, `401`, `404`).

---

## The feature in one paragraph

A **dashboard** is a named canvas that arranges **report widgets** on a 12-column grid. Each widget
references a **saved report** (built in the reports module) and carries its grid position. The
dashboard stores no query logic — to render it you fetch the dashboard, then run each widget's
report via the **reports client** (`runSavedReport(reportId, …)`) and render the returned
`ReportData` by its `type`. There are no dashboard-level filters in v1; each report carries its own.

---

## Endpoints to implement (see `api-spec.yaml`)

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/api/v1/dashboards` | Create → `DashboardResponse` |
| `GET` | `/api/v1/dashboards` | List the user's dashboards (summaries) |
| `GET` | `/api/v1/dashboards/{id}` | Get one (widgets enriched with report metadata) |
| `PUT` | `/api/v1/dashboards/{id}` | Update (replaces name, description, full widget set) |
| `DELETE` | `/api/v1/dashboards/{id}` | Delete |

Suggested methods (adapt to repo style): `createDashboard(body)`, `listDashboards()`,
`getDashboard(id)`, `updateDashboard(id, body)`, `deleteDashboard(id)`.

> **No dashboard data endpoint.** Widget data is fetched **per-widget** by calling the reports
> client's `runSavedReport(widget.reportId, { page?, size? })` (page/size only matter for TABLE
> widgets). Reuse the existing `ReportData` union and renderers from the reports client.

---

## Types to model

```ts
import type { ReportType, ReportData } from '<reports-client>'; // 'KPI' | 'CHART' | 'TABLE'; data union

interface WidgetLayout { x: number; y: number; w: number; h: number; } // 12-col grid

// The widget as stored / sent (request side)
interface DashboardWidget {
  id: string;          // client-generated, UNIQUE within the dashboard (use as the grid item key)
  reportId: string;    // uuid of a saved report
  title?: string | null; // optional display override; null = use the report's own name
  layout: WidgetLayout;
}

// The widget as returned (enriched with server-resolved report metadata)
interface WidgetResponse {
  id: string;
  reportId: string;
  title: string | null;
  layout: WidgetLayout;
  report: { name: string | null; type: ReportType | null; available: boolean };
}

interface CreateDashboardRequest { name: string; description?: string | null; widgets: DashboardWidget[]; }
interface UpdateDashboardRequest { name: string; description?: string | null; widgets: DashboardWidget[]; }

interface DashboardResponse {
  id: string; name: string; description: string | null;
  widgets: WidgetResponse[]; createdAt: string; updatedAt: string;
}
interface DashboardSummaryResponse {
  id: string; name: string; description: string | null;
  widgetCount: number; createdAt: string; updatedAt: string;
}
```

---

## Rendering flow (for when the UI is built later — encode it in the client's shape/JSDoc)

1. `getDashboard(id)` → `DashboardResponse`.
2. Lay each `widget` onto a 12-column grid using `widget.layout` (`{x,y,w,h}`); use `widget.id` as
   the stable item key.
3. For each widget where `widget.report.available === true`, pick the renderer from
   `widget.report.type` and call the reports client `runSavedReport(widget.reportId, { page, size })`
   (page/size for TABLE widgets) → render the `ReportData`.
4. Widgets with `report.available === false` (report deleted or not yours) → render a placeholder
   ("report no longer available"); do **not** call `runSavedReport` for them.
5. Editing the grid (drag / resize / add / remove) = mutate the `widgets[]` array and `PUT` the
   whole dashboard back via `updateDashboard`.

---

## Decisions you MUST honour

1. **Widgets reference saved reports by id** — they do not embed report definitions. To add a widget,
   the user picks an existing saved report (`GET /api/v1/reports`).
2. **Graceful unavailable** — a widget can outlive its report. Always handle `report.available`;
   never assume `report.name`/`report.type` are non-null.
3. **No save-time existence check** — the backend does NOT reject a `reportId` that doesn't exist, so
   saving never fails just because some unrelated report was deleted. The client should still only
   let users *add* reports that currently exist, but must tolerate stale refs on render.
4. **12-column grid bounds** — validate before save: `x` 0–11, `w` 1–12, `x + w <= 12`, `y >= 0`,
   `h >= 1`. The server enforces the same and returns `400` otherwise. Widget `id`s must be unique
   within a dashboard.
5. **Widget `id` is client-generated** (e.g. a UUID) and is the grid key; the same report may appear
   in multiple widgets (distinct ids).
6. **Per-widget data fetch** via the reports client — there is no batch/dashboard data endpoint, and
   no global dashboard filters in v1.
7. **Report types are UPPERCASE** (`KPI`/`CHART`/`TABLE`) — reuse the reports client's `ReportType`.
8. **Update replaces the whole dashboard** (`name` + `description` + `widgets[]`); send the full
   widget set, not a delta.

---

## Deliverables
- A typed `dashboards` API module (types + the five methods) consistent with the existing API layer
  and the reports client.
- Optional tiny helpers if they fit the repo (e.g. a `newWidget()` factory that mints a UUID + a
  default layout, an `isWidgetAvailable(w)` guard). Keep them minimal.
- Brief JSDoc on each method, including the per-widget render flow note.

