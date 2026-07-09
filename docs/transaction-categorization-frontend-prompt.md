# Implementation Prompt — Transaction Categorization (CLIENT)

You are implementing the frontend for the **transaction categorization** feature in the FinanceOS client repo: `financeos-client` (sibling directory of `financeos-server`). Stack: Next.js 16 App Router with React Server Components, React 19, TypeScript strict, Tailwind CSS 3.4, shadcn/ui ("new-york") on Radix primitives, `lucide-react` icons, `sonner` toasts. There is **no** client state library — data is fetched in RSC pages via `src/lib/apiClient.ts` and mutated through Server Actions in `src/actions/*`. Path alias `@/*` → `./src/*`.

The backend is implemented from a matching server prompt. The API contract in section 2 is shared and authoritative — code against it exactly; do not invent fields or endpoints.

## 0. Ground rules — read first

- **Do not invent anything.** Every file and pattern referenced here exists and was verified. READ each file before editing it. Reuse existing components; check `src/components/ui/*` and `src/components/*` before creating anything new.
- **If anything is ambiguous or contradicts what you find in the code, STOP and ask.** Do not guess.
- Follow the established data-flow pattern strictly: RSC page fetches via `*Api` from `apiClient.ts` (server-only — it forwards the `FINANCEOS_SESSION` cookie) → passes props to a `'use client'` component → mutations go through Server Actions returning the `ApiResult<T>` discriminated union (`{success:true,data} | {success:false,error}`) and calling `revalidatePath(...)`. Mirror `src/actions/transactions.ts`.
- Verify with `npm run build` (and lint) before declaring done.

## 1. Feature summary (what the user sees)

1. Transactions that need review now carry **reasons** — the UI must show *why*: unreconciled, unverified category, possible duplicate.
2. A new **Categorization Rules** page manages the merchant→categories mapping list: verify LLM-suggested rules, edit their categories, create/delete rules.
3. Verifying a rule (or batch-approving transactions) clears the related review items server-side — the client just refetches.

## 2. API contract (authoritative — matches the server implementation)

New enum-like string union: `ReviewReason = 'UNRECONCILED' | 'CATEGORY_UNVERIFIED' | 'DUPLICATE_SUSPECT' | 'OTHER'`.

`TransactionResponse` now includes two new fields:
```ts
reviewReasons: ReviewReason[];      // empty unless reviewType === 'NEEDS_REVIEW'
appliedRuleId: string | null;       // which rule categorized it, if any
```

`RuleResponse`:
```ts
{
  id: string; merchantKey: string; displayName: string | null;
  categories: Category[];           // same Category shape as categories.types.d.ts
  verified: boolean; source: 'LLM' | 'USER';
  appliedCount: number; lastAppliedAt: string | null; createdAt: string;
}
```

| Endpoint | Notes |
|---|---|
| `GET /api/v1/rules?page&size&sort&verified={true\|false\|omitted}&search=` | Spring `Page<RuleResponse>` — identical paged envelope to `PagedTransaction` (`content`, `totalElements`, `totalPages`, `size`, `number`, `first`, `last`, `empty`). Default server sort: unverified first, then last-applied desc. |
| `POST /api/v1/rules` body `{merchantKey: string, displayName?: string, categoryIds: string[]}` | 201 → `RuleResponse`. 409 if the (normalized) merchant key already exists. Server normalizes the key (uppercases, strips numbers/punctuation) and requires ≥ 3 chars after normalization. |
| `PUT /api/v1/rules/{id}` body `{merchantKey?, displayName?, categoryIds?}` | → `RuleResponse`. Changing categories retroactively re-applies to that rule's transactions (server-side). |
| `POST /api/v1/rules/{id}/verify` | → `RuleResponse`. Server also clears `CATEGORY_UNVERIFIED` on all transactions categorized by this rule. |
| `DELETE /api/v1/rules/{id}` | 204. Transactions keep their categories. |

Transactions search DSL (`POST /api/v1/transactions/search`) gains a filterable field `reviewReason` (ENUM; values above; operators `is` / `in` — check `src/components/transactions/catalog.ts` for how enum fields declare operators and mirror the existing `reviewType` entry).

## 3. Cleanup task (do this first)

`categoriesApi.categorizeDescription()` in `src/lib/apiClient.ts` (~line 382-417 region) and the `categorizeDescription` server action in `src/actions/categories.ts` call `POST /api/v1/categorize`, **which does not exist on the server and never will**. Delete both, plus any imports. Verify with a project-wide search for `categorize` that nothing else references them.

## 4. Types

- `src/lib/transaction.types.d.ts`: add `ReviewReason`; add `reviewReasons?: ReviewReason[]` and `appliedRuleId?: string | null` to the `Transaction` read type; also fix `TransactionSource` — the server enum has a 4th value `'file_upload'` that is currently missing.
- New `src/lib/rules.types.d.ts`: `RuleResponse` (name it `CategoryRule`), `CreateRuleRequest`, `UpdateRuleRequest`, `PagedRules` (mirror `PagedTransaction`'s shape).

## 5. API client + server actions

- `src/lib/apiClient.ts`: add `rulesApi` with `list(params)`, `create(body)`, `update(id, body)`, `verify(id)`, `remove(id)` — same style as `transactionsApi` (private `request<T>` helper, `URLSearchParams` for query strings).
- New `src/actions/rules.ts` (`'use server'`): `createRule`, `updateRule`, `verifyRule`, `deleteRule` — each wrapping `rulesApi`, returning `ApiResult<T>`, and calling `revalidatePath('/rules')` plus `revalidatePath('/transactions')` and `revalidatePath('/transactions/review')` (rule mutations change transaction review state server-side). Mirror the error-handling pattern in `src/actions/transactions.ts` exactly.
- Reads (rules list, categories) happen in the RSC page directly via `rulesApi` / `categoriesApi` — no read actions needed.

## 6. Review-reason indicators on transactions

**`src/components/transactions/TransactionCard.tsx`** — currently renders a single `<Badge variant="warning">Needs Review</Badge>` when `reviewType === 'NEEDS_REVIEW'` (~line 146). Replace with one badge per reason (badge variants available: `default | secondary | info | warning | outline`):

| Reason | Label | Suggested variant |
|---|---|---|
| `UNRECONCILED` | `Unreconciled` | `warning` |
| `CATEGORY_UNVERIFIED` | `Category unverified` | `info` |
| `DUPLICATE_SUSPECT` | `Possible duplicate` | `warning` |
| `OTHER` | `Needs review` | `outline` |

Fallback: if `reviewType === 'NEEDS_REVIEW'` but `reviewReasons` is empty/undefined (stale data), render the old single "Needs Review" badge.

**`src/components/transactions/ReviewBrowser.tsx`** — keeps its hard-coded `reviewType is NEEDS_REVIEW` filter. Add a reason filter above the list: a row of toggle chips (`All` / `Unreconciled` / `Category` / `Duplicate` — reuse existing button/badge primitives, no new dependency) that, when one is active, appends `{field: 'reviewReason', operator: 'is', value: '<REASON>'}` to the search filters. Also render the per-row reason badges (reuse whatever you extract for TransactionCard — extract a small `ReviewReasonBadges` component rather than duplicating).

**`src/components/transactions/catalog.ts`** — add `reviewReason` to `TRANSACTIONS_CATALOG` as a static-enum field (mirror the existing `reviewType` entry) so the main list's filter panel picks it up too.

No changes needed to the batch Approve/Delete toolbar — approving already calls `batchReviewTransactions(ids, 'MANUALLY_REVIEWED')` and the server now handles rule-verification side effects. Just make sure the list refetches after (it already does via `revalidatePath`).

## 7. Categorization Rules page

**Route:** `src/app/(protected)/rules/page.tsx` (RSC). Fetch first page of rules (`rulesApi.list`) + all categories (`categoriesApi.list()`), pass to a client component `src/components/rules/RulesBrowser.tsx`.

**Navigation:** add "Rules" entries to the `navItems` array in `src/components/layout/Sidebar.tsx` AND the route arrays in `src/components/layout/MobileNav.tsx` (read both files first — mirror an existing entry; pick a fitting lucide icon such as `Tags`).

**`RulesBrowser` (client component):**
- Tabs or chips: `Unverified` (default) / `Verified` / `All` → drives the `verified` query param. Search input (debounced, like `TransactionsBrowser`) → `search` param. Pagination via the existing `TablePagination` (`src/components/reports/views/TablePagination.tsx`).
- Each rule row/card shows: `displayName` (fall back to `merchantKey`), the `merchantKey` in muted mono, category badges, `verified` state (badge), `source` (small `LLM`/`Manual` tag), `appliedCount` ("used 12×"), relative `lastAppliedAt`.
- Row actions:
  - **Verify** (only when unverified) → `verifyRule(id)`; success toast: "Rule verified — matching transactions cleared from review".
  - **Edit** → dialog (reuse `src/components/ui/dialog.tsx` patterns) with display-name input and category multi-select using the existing `src/components/Combobox.tsx` (it already supports multi-select + inline category creation). No upper limit on category count; require at least 1. Save → `updateRule`.
  - **Delete** → wrap in the existing `ConfirmationDialog` (`src/components/ConfirmationDialog.tsx`); body must state "Transactions already categorized by this rule keep their categories."
- **Create rule** button → same dialog as Edit plus a merchant-key input. Client-side validation: ≥ 3 chars after stripping digits/punctuation; helper text explaining matching ("Matches any transaction whose description contains this text — numbers and punctuation are ignored"). Handle the 409 duplicate-key error with a clear toast.
All mutations: optimistic UI is NOT required — follow the existing pattern of awaiting the action, toasting via `sonner`, and relying on `revalidatePath` to refresh.

## 8. Edge cases

- Rule with zero categories should be impossible from the UI (require ≥ 1 in create/edit validation) but render defensively if the API returns one.
- Empty states: rules page with no rules ("Transactions you ingest will generate rules automatically — or create one"), review page with a reason filter that matches nothing.
- `reviewReasons` may be missing on older cached responses — treat as optional everywhere (`?.` / default `[]`).

## 9. Out of scope — do NOT build

- No categories management page (categories CRUD UI beyond what `Combobox` inline-create already does).
- No backfill of historical transactions — no backfill button, action, or endpoint (the server does not implement one).
- No client-side rule matching/preview, no regex input, no bulk rule verify (single verify only for now).
- No changes to reports, dashboards, accounts, or gmail screens.

## 10. Before you start

Read, in this order: `src/lib/apiClient.ts`, `src/actions/transactions.ts`, `src/actions/categories.ts`, `src/lib/transaction.types.d.ts`, `src/components/transactions/TransactionCard.tsx`, `ReviewBrowser.tsx`, `TransactionsBrowser.tsx`, `catalog.ts`, `src/components/Combobox.tsx`, `ConfirmationDialog.tsx`, `src/components/layout/Sidebar.tsx`, `MobileNav.tsx`, one reports page under `src/app/(protected)/` as an RSC reference. Then post any questions you have. Only start writing code once you have zero open questions.
