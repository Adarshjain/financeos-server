# Statement parser corpus tools

Regression measurement for `com.financeos.statement.parser` over a private corpus of real statements. The corpus is
private: it lives outside git (for example `$HOME/financeos-statement-corpus`) and must never be committed. Both tools are
off in normal test runs.

Corpus layout: `manifest.tsv` (header row with at least `sha256`, `ext`, `locked`, `readable_sha256`, `kind`, `issuer`,
`in_scope`) and `files/<sha256>.<ext>`. Only distinct non-empty `readable_sha256` rows with `in_scope=Y` are parsed
(`-Dcorpus.scope=all` takes every row).

## Run

```
./mvnw -o -q test -Dtest=CorpusRunner -Dcorpus.dir=$HOME/financeos-statement-corpus -Dcorpus.out=/tmp/run-head -Dsurefire.failIfNoSpecifiedTests=false
```

Optional: `-Dcorpus.timeoutSec=60` (a timeout is recorded as a `Timeout` failure and the run continues), `-Dcorpus.scope=all`.
`-Dcorpus.reader=v1` ignores every layout profile, `=layout` reads each file with its matching profile (as if ACTIVE); unset = the configured modes.
Output in `corpus.out`: one pretty-printed `<readable_sha256>.json` snapshot per file and `results.tsv` (sha256, kind, issuer,
bytes, wall_ms, failure_type, statement_type, verdict, mode, chain_pct, checksum_ok, txn_count, rows_dropped, null_fields, layout_id).
It is a measurement: the test passes whenever the run completes. One summary line is printed at the end.

## Diff two runs

```
./mvnw -o -q test -Dtest=CorpusDiff -Dcorpus.diff.base=/tmp/run-base -Dcorpus.diff.head=/tmp/run-head -Dsurefire.failIfNoSpecifiedTests=false
```

Writes `<head>/diff.md` (override with `-Dcorpus.diff.report=FILE`): counts, then changed files grouped by issuer with
`field: old → new`, and row changes as added / removed / changed counts with up to 5 examples. The report contains statement
values: keep it on this machine. `-Dcorpus.diff.family=<layout id>` compares only the files whose head results.tsv row has that layout_id; `-Dcorpus.diff.ignore=signSource,layoutId,reader` leaves those field names out of the comparison. Add `-Dcorpus.diff.failOnChange=true` to fail when any file changed or exists on one side only.

## Rule for any parser change

Run before and after, read the diff, and explain every changed file. Take the base run on the unchanged parser first
(for example from a clean worktree), then the head run with your change.
