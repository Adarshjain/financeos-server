# Layout profiles

A second statement reader beside the generic one ("v1"). One small declarative `LayoutProfile` per statement layout;
v1 stays the fallback for layouts without a profile.

## Write a profile

Put a class in `layout/profiles/` that builds a `LayoutProfile` (see `AxisCardDtda2024`) and add it to
`LayoutRegistry.defaults()` (order matters: first match wins). Declare, using labels, regexes and coordinates only:

- `issuer`, `product` (CARD or BANK), optional `bank` name, `requires(...)` (text that tells look-alike layouts apart).
- `headerLabel(name, regex)` in order, all on one line; `headerRejects(...)` for wider look-alikes.
- Columns by role, x-range relative to a header label of the page (`fromLabel`, `column(role, at(..), at(..))`);
  absolute ranges only when nothing moves.
- `dateFormats` (java patterns; yearless takes the year from the period), the sign rule (`suffix`, `sign(...)`),
  `continuation(below, above)`, `endMarker`, `skipLine`, `sectionHeader`, `order`.
- `summary(...)` per value: label regex, position (right on line / below by x / nth amount below), type, `creditIsNegative`
  / `creditIsZero`. Values are read only from the table pages and trailing summary pages, never terms text.
- A CARD profile must account for every key in `CardFieldKeys`: `summary(key, ...)` or `notPrinted(key)`.
- A page without the header continues the table (`continuesWithoutHeader`, default true).

### More DSL

- Single-date statements: `summary(MetaTarget.PERIOD_END, label).type(DATE)` (and `PERIOD_START`); a profile's
  `STATEMENT_DATE` survives the shared finish step (v1 never sets one, so v1 is unchanged).
- Values that are not one clean word: `.capture(regex)` on any summary runs over the label line's text (words joined by
  single spaces; `.captureLines(n)` also the next n lines) and parses group 1 by the declared type (DATE_RANGE: two groups
  or two dates; MASKED_NUMBER keeps inner spaces, `.compact()` removes them). No match or unparseable group: no value.
- `headerLines(n)`: the header may span up to n lines; each label anchors by its own x0; the table starts after the last.
- `IGNORE` columns swallow any token (dates, times, amounts, signs) and are never counted as unread.
- A `FOR_GOOD` end marker on a heading keeps every sub-table below it out of the rows.

- Card last 4 for rows: a section header above the row wins; otherwise `cardLast4From(regex)` (group 1 over the lines
  before the first table header) and/or `cardLast4FromAccountNumber()`. The generic reader seeds its last 4 from the first
  header line carrying a card number, which is the same number a profile's ACCOUNT_NUMBER summary captures.
- `prefix(creditRegex, debitRegexOrNull, defaultDirection)`: the sign marker is the token right before the amount (or before
  a currency glyph in front of it). Same adjacency and stray-token rules as `suffix`.
- `continuationFromX(role, x0, x1)` / `continuationFromFirstDate(role, dx0, dx1)`: columns for header-less continuation pages
  only. Absolute ranges are the robust choice (a template prints those pages with fixed geometry, and the roles shift by
  different amounts, so no single offset works); the first-date anchor (leftmost full date token of the page) suits a
  uniform page shift and falls back to the previous page's geometry when the page has no date. Default stays: reuse the
  previous page's header-anchored columns.
- A Cr/Dr-like token or amount inside an `IGNORE` column is ignored (an amount with its own marker, e.g. a cashback cell);
  the row's marker is still the token adjacent to the AMOUNT-column amount.
- Layout variants of one family (same labels, different x-arrangement) are separate profiles told apart by
  `requiresLabelAt(label, minX, maxX)` (the x0 of that header label must lie in the range), each with its own relative
  columns and its own acceptance diff. Chosen over alignment-aware columns or alternative column sets because it keeps
  every profile one static, reviewable description and the acceptance rule per variant.

- `ValueType.TEXT`: the raw value as a String (RIGHT_ON_LINE / BELOW_BY_X: the next word(s), `.maxWords(n)`, default 1, lone
  punctuation skipped; with `capture(regex)`: group 1). Targets: `ACCOUNT_NUMBER` (MASKED_NUMBER or TEXT), `IFSC`,
  `HOLDER_NAME` (TEXT). The builder rejects target/type pairs the reader cannot store (legacy summary keys take AMOUNT or
  INTEGER, `payment_due_date` DATE). Like for like with the generic reader: IFSC `(?-i:\b([A-Z]{4}0[A-Z0-9]{6})\b)` (the
  capture flag is case-insensitive, so switch it off inline), holder name
  `(?:customer name|account holder|a/c holder|holder name|name)\s*[:\-]\s*(.+)`, first line wins.
- An unlabelled number (a masked card number alone on a line under the header): `summary(ACCOUNT_NUMBER, <mask regex>)
  .capture("(<mask regex>)")`; the line is a summary line, never a continuation or unread.
  `accountNumberFromSectionHeader()`: the section header's group 1 is the full number (ACCOUNT_NUMBER fallback; each row's
  last 4 are its last 4 digits).
- `cardLast4From` / `cardLast4FromAccountNumber` apply to rows of every strategy (a custom strategy's own last 4 wins).
  A BANK profile reproduces the generic reader's "last 4 of the account number" with `cardLast4FromAccountNumber()`.
- SEPARATE_COLUMNS: a printed zero in the unused column is blank; both zero is a zero row (the generic reader keeps it);
  two real amounts, or two figures in one cell, are unread. Narration below such a row works with `continuation(below, 0)`.

## Acceptance rule (before a profile goes ACTIVE)

Run the corpus twice, `-Dcorpus.reader=v1` and `-Dcorpus.reader=layout`, then `CorpusDiff` with
`-Dcorpus.diff.family=<id> -Dcorpus.diff.ignore=signSource,layoutId,reader`. Every file of the layout must match the profile
and be identical to v1, or each difference must be explained (v1 wrong, with the PDF as proof). No file outside the layout
may match. Bump `PARSER_VERSION` in the same commit that makes any profile ACTIVE by default.

## Modes (`app.statement.layouts.<id>`, `...default`)

- OFF: v1 only. SHADOW (default): v1 is returned; with a registered `ShadowListener` the profile also runs and the two
  results are compared (counts and field names, never values; `meta.bank`, `signSource`, `layoutId`, `reader` ignored).
  ACTIVE: per profile id only (`default` may not be ACTIVE).
- `-Dcorpus.reader=v1|layout` forces a reader in the corpus tool (layout skips the guard).

## The ACTIVE guard

The profiled result is returned only when it has rows, `unreadAmountLines == 0`, an account or card number, a period end
and verdict AUTO-INGEST. Otherwise v1 also runs, the better verdict wins (ties go to v1) and a `fallback` is reported.
