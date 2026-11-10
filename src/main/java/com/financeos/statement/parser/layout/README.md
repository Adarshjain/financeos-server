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
