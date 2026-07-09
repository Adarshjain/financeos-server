#!/usr/bin/env python3
"""
Generic bank-statement PDF parser — zero templates, zero AI.

Infers the layout from structural invariants shared by all machine-generated
statements, and uses the running-balance arithmetic chain as an oracle to
resolve ambiguity and to prove the parse correct.

Usage:
    python3 parse_statement.py statement.pdf
    python3 parse_statement.py statement.pdf --password SECRET
    python3 parse_statement.py statement.pdf --json          # machine-readable output
    python3 parse_statement.py statement.pdf --debug         # show inference decisions

Dependencies: pip install pdfplumber
"""

import argparse
import json
import re
import sys
from collections import defaultdict
from datetime import datetime

try:
    import pdfplumber
except ImportError:
    sys.exit("Missing dependency. Run: pip install pdfplumber")

# ----------------------------------------------------------------------------
# Universal vocabulary (bank-agnostic — every bank draws from these words)
# ----------------------------------------------------------------------------

HEADER_KEYWORDS = {
    "date": ["date", "txn date", "transaction date", "value date", "value dt", "post date"],
    "description": ["description", "narration", "particulars", "details", "transaction details", "remarks"],
    "debit": ["debit", "withdrawal", "withdrawals", "withdrawal amt", "debit amount", "dr", "paid out"],
    "credit": ["credit", "deposit", "deposits", "deposit amt", "credit amount", "cr", "paid in"],
    "balance": ["balance", "closing balance", "running balance", "available balance"],
    "amount": ["amount", "amount (inr)", "transaction amount"],
    "ref": ["ref", "ref no", "chq", "cheque", "chq/ref no", "cheque no", "reference"],
}

ACCOUNT_LABEL_RE = re.compile(
    r"(?:account|a/c|a\.c\.|acct|card)[\w\-/]*\s*(?:no|number|#)?\.?\s*[:.\-]?\s*"
    r"([0-9Xx*](?:[0-9Xx*\-]{4,22})[0-9Xx*])", re.I)
IFSC_RE = re.compile(r"\b([A-Z]{4}0[A-Z0-9]{6})\b")
BANK_BRAND_RE = re.compile(r"\b(bank|hsbc|citi|amex|american express|barclays)\b", re.I)
CARD_MASK_RE = re.compile(
    r"\b([0-9Xx*]{4}\s+[0-9Xx*]{4}\s+[0-9Xx*]{4}\s+[0-9Xx*]{4}|[0-9Xx*]{14,19})\b")
NAME_LABEL_RE = re.compile(
    r"(?:customer name|account holder|a/c holder|holder name|name)\s*[:\-]\s*(.+)", re.I)
PERIOD_LINE_RE = re.compile(r"(?:statement|period|from)", re.I)
OPENING_BAL_RE = re.compile(r"(?:opening balance|balance b/f|brought forward|b/f)", re.I)
CLOSING_BAL_RE = re.compile(r"(?:closing balance|balance c/f|carried forward)", re.I)

DATE_TOKEN_RE = re.compile(
    r"^(\d{1,2}[/\-.]\d{1,2}[/\-.]\d{2,4}"          # 01/03/2026, 01-03-26
    r"|\d{1,2}[/\-. ][A-Za-z]{3,9}[/\-. ]\d{2,4}"    # 01-Mar-2026, 01 March 26, 05 Jun 2026
    r"|[A-Za-z]{3,9} \d{1,2} \d{2,4}"                # Jun 05 2026 (comma stripped)
    r"|\d{1,2}[A-Za-z]{3,9}\d{2,4}"                  # 28May2026, 31MAY26 (glued)
    r"|\d{1,2}\s?[A-Za-z]{3,9}"                      # 20MAY, 20 May (yearless)
    r"|\d{4}[/-]\d{2}[/-]\d{2})$"                    # 2026-03-01, 2026/05/29
)

DATE_FORMATS = [
    "%d/%m/%Y", "%d/%m/%y", "%m/%d/%Y", "%m/%d/%y",
    "%d-%m-%Y", "%d-%m-%y", "%d.%m.%Y", "%d.%m.%y",
    "%d-%b-%Y", "%d-%b-%y", "%d/%b/%Y", "%d/%b/%y",
    "%d %b %Y", "%d %b %y", "%d %B %Y", "%Y-%m-%d",
    "%d-%B-%Y", "%b %d %Y", "%B %d %Y", "%b %d %y",
    "%d%b%Y", "%d%b%y", "%d%B%Y", "%Y/%m/%d",        # glued/ISO variants
    "%d%b", "%d%B", "%d %b", "%d-%b", "%d/%b",       # yearless (year from period)
]

TIME_RE = re.compile(
    r"^(\d{1,2}:\d{2}(:\d{2})?(AM|PM|am|pm)?|AM|PM|am|pm|IST|Hrs|hrs)$")

# Amount: needs decimal point or comma grouping so bare ref numbers don't match.
# The currency prefix includes "C" and "`" — rupee glyphs extract as those in
# HDFC and ICICI fonts respectively.
AMOUNT_RE = re.compile(
    r"^\(?(?:[₹$€£C`]|Rs\.?|INR)?\s?(-?\d{1,3}(?:,\d{2,3})*\.\d{1,2}|-?\d+\.\d{1,2})\)?\s*(Cr|CR|cr|Dr|DR|dr)?\.?$"
)

# Currency glyphs that extract as their own word ("C 1,787.61")
CURRENCY_TOKENS = {"C", "₹", "$", "€", "£", "Rs", "Rs.", "INR", "`"}

Y_TOL = 3.0        # words within this vertical distance form one line
COL_TOL = 12.0     # right-edge x positions within this distance form one column
EPS = 0.011        # tolerance for balance-chain float comparison


def norm_token(t):
    """Trim decoration that banks glue onto values: trailing commas/semicolons
    and stray table-rule glyphs ("18/05/2026|")."""
    return t.strip().strip("|").rstrip(",;:").strip()


def date_anchor(words, max_start=3, max_span=3):
    """
    Find a date at/near the start of a line. Dates may span several words
    ("05 Jun 2026,") and may not be the very first column (serial-no columns).
    Returns (start_idx, span, normalized_date_string) or None.
    """
    for start in range(min(max_start, len(words))):
        for span in range(min(max_span, len(words) - start), 0, -1):
            cand = " ".join(norm_token(w["text"]) for w in words[start:start + span])
            if DATE_TOKEN_RE.match(cand):
                return start, span, cand
    return None


def find_dates_in_words(words, fmts):
    """Scan a line for date values (1-3 word windows), parsed with any of fmts."""
    found, i = [], 0
    while i < len(words):
        consumed = 0
        for span in (3, 2, 1):
            if i + span > len(words):
                continue
            cand = " ".join(norm_token(w["text"]) for w in words[i:i + span])
            if not DATE_TOKEN_RE.match(cand):
                continue
            for fmt in fmts:
                try:
                    found.append(datetime.strptime(cand, fmt))
                    consumed = span
                    break
                except ValueError:
                    pass
            if consumed:
                break
        i += consumed or 1
    return found


def parse_amount(token):
    """Return (value, explicit_sign) or None. Sign: +1 Cr, -1 Dr/parens/minus, 0 unknown."""
    t = token.strip()
    trailing_minus = t.endswith("-") and len(t) > 1  # "29,132.00-" accounting style
    if trailing_minus:
        t = t[:-1].strip()
    m = AMOUNT_RE.match(t)
    if not m:
        return None
    value = float(m.group(1).replace(",", ""))
    sign = 0
    if m.group(2):
        sign = 1 if m.group(2).lower() == "cr" else -1
    if t.startswith("(") or value < 0 or trailing_minus:
        value, sign = abs(value), -1
    return value, sign


SUMMARY_WORDS_RE = re.compile(
    r"\b(total|outstanding|balance|purchases|installments|summary|brought|carried)\b", re.I)


def is_summary_line(text):
    """Summary/section furniture: ≥2 distinct summary words. One alone can be a
    legit merchant name; 'TOTAL PURCHASE OUTSTANDING' style lines have several."""
    return len(set(w.lower() for w in SUMMARY_WORDS_RE.findall(text))) >= 2


# ----------------------------------------------------------------------------
# Stage 1+2: extraction, line reconstruction, page-furniture stripping
# ----------------------------------------------------------------------------

def extract_lines(pdf_path, password):
    """Return list of lines; each line is a list of word dicts {text,x0,x1,top,page}."""
    try:
        pdf = pdfplumber.open(pdf_path, password=password or "")
    except Exception as e:
        blob = f"{type(e).__name__} {e!r} {getattr(e, '__cause__', '')!r}".lower()
        if "password" in blob or "decrypt" in blob:
            sys.exit("ERROR: PDF is password-protected and the password is missing or wrong. "
                     "Pass it with --password.")
        raise

    lines = []
    with pdf:
        for pno, page in enumerate(pdf.pages):
            words = page.extract_words(keep_blank_chars=False)
            # Sweep-group into lines: a word joins the current line if its top
            # is within tolerance of the line's anchor. Cells in one visual row
            # can sit on slightly different baselines (mixed fonts), so fixed
            # buckets split rows that this correctly keeps together.
            page_rows = []
            for w in sorted(words, key=lambda w: (w["top"], w["x0"])):
                if page_rows and w["top"] - page_rows[-1][0] <= 3.8:
                    page_rows[-1][1].append(w)
                else:
                    page_rows.append((w["top"], [w]))
            for _top, ws in page_rows:
                ws = sorted(ws, key=lambda w: w["x0"])
                for w in ws:
                    w["page"] = pno
                lines.append(ws)

    if not lines:
        sys.exit("ERROR: no text layer found (scanned/image PDF?). OCR branch is out of scope for this test script.")

    # Strip repeated page furniture: identical text seen on an earlier page.
    seen_on_page = {}
    kept = []
    for ln in lines:
        text = " ".join(w["text"] for w in ln)
        page = ln[0]["page"]
        if text in seen_on_page and seen_on_page[text] != page:
            continue  # repeat of a header/footer from another page
        seen_on_page.setdefault(text, page)
        kept.append(ln)
    return kept


def line_text(ln):
    return " ".join(w["text"] for w in ln)


# ----------------------------------------------------------------------------
# Stage 5: date-format inference
# ----------------------------------------------------------------------------

def infer_date_format(tokens, debug=False):
    """Pick the strptime format that parses the most tokens; prefer chronological
    order as a tie-break. Rows that don't parse with the winner are junk (stray
    date-bearing lines that survived the table filters) and get dropped later."""
    best, best_score = None, (0, -1)
    for fmt in DATE_FORMATS:
        parsed = []
        for t in tokens:
            try:
                parsed.append(datetime.strptime(t, fmt))
            except ValueError:
                pass
        ordered = sum(1 for a, b in zip(parsed, parsed[1:]) if b >= a)
        score = (len(parsed), ordered)
        if score > best_score:
            best, best_score = fmt, score
    if debug and best:
        print(f"[debug] date format inferred: {best} "
              f"({best_score[0]}/{len(tokens)} rows)", file=sys.stderr)
    return best


# ----------------------------------------------------------------------------
# Stage 3+4+7: table detection, row grammar, continuation merging
# ----------------------------------------------------------------------------

def find_header_row(lines, first_txn_idx):
    """Look upward from the first transaction row for a line with >=3 column keywords."""
    flat = [kw for kws in HEADER_KEYWORDS.values() for kw in kws]
    for i in range(first_txn_idx - 1, max(first_txn_idx - 8, -1), -1):
        text = line_text(lines[i]).lower()
        hits = sum(1 for kw in flat if kw in text)
        if hits >= 3:
            return i
    return None


def collect_rows(lines, debug=False):
    """Split lines into: pre-table header zone, transaction rows, continuations."""
    anchors = [date_anchor(ln) if ln else None for ln in lines]

    def block_has_amount(i):
        # A transaction's amounts may sit on the lines below its date line
        # (multi-line block layouts); look ahead until the next block starts.
        # An amount-bearing line closes THIS block even if it starts with a
        # date (value-date + amount + balance lines) — only an amount-less
        # anchored line starts a new block.
        for j in range(i, min(i + 7, len(lines))):
            if any(parse_amount(norm_token(w["text"])) for w in lines[j]):
                return True
            if j > i and anchors[j]:
                return False
        return False

    # A transaction row is date-anchored AND has an amount in its block
    # (filters out stray dates in the header zone, e.g. statement-period lines).
    cand = [i for i, (ln, a) in enumerate(zip(lines, anchors))
            if a and block_has_amount(i) and not is_summary_line(line_text(ln))]
    if not cand:
        sys.exit("ERROR: no date-anchored rows found — cannot locate a transaction table. "
                 "Run with --debug and share the output.")

    # Keep only rows whose date sits at a consistent x-position (the date
    # column). Clustered per page — some statements shift the table between
    # the first page and the rest.
    def ax(i):
        return lines[i][anchors[i][0]]["x0"]
    kept_cand = []
    for page in {lines[i][0]["page"] for i in cand}:
        pc = [i for i in cand if lines[i][0]["page"] == page]
        sizes = {i: len([j for j in pc if abs(ax(j) - ax(i)) <= 15]) for i in pc}
        biggest = max(sizes.values())
        # Keep every column with >=2 rows (statements can have a main date
        # column plus an indented sub-transaction column); singletons survive
        # only if nothing bigger exists on the page.
        kept_cand += [i for i in pc if sizes[i] >= 2 or sizes[i] == biggest]
    cand = sorted(kept_cand)

    # Majority vote on the date format across candidates, and drop the rows
    # that don't conform (stray date-bearing lines on offer/T&C pages). This
    # must happen BEFORE the table extent is fixed — a junk candidate on a
    # late page would otherwise stretch the table over the real summary lines.
    date_fmt = infer_date_format([anchors[i][2] for i in cand], debug=debug)
    if not date_fmt:
        sys.exit("ERROR: could not infer a date format for the transaction rows.")

    def _conforms(i, fmt):
        try:
            datetime.strptime(anchors[i][2], fmt)
            return True
        except ValueError:
            return False
    keep = [i for i in cand if _conforms(i, date_fmt)]
    rest = [i for i in cand if not _conforms(i, date_fmt)]
    # Some statements use a second date format for indented sub-transactions
    # (e.g. "28May2026" main rows with "31MAY26" card-swipe rows). Accept a
    # secondary format if it consistently parses 2+ of the leftover rows.
    date_fmt2 = None
    if len(rest) >= 2:
        fmt2 = infer_date_format([anchors[i][2] for i in rest], debug=False)
        if fmt2:
            second = [i for i in rest if _conforms(i, fmt2)]
            if len(second) >= 2:
                date_fmt2 = fmt2
                keep = sorted(keep + second)
                rest = [i for i in rest if i not in second]
                if debug:
                    print(f"[debug] secondary date format: {fmt2} ({len(second)} rows)",
                          file=sys.stderr)
    if rest and debug:
        print(f"[debug] dropped {len(rest)} date-bearing lines not matching the format(s): "
              f"{[anchors[i][2] for i in rest]}", file=sys.stderr)
    cand = keep
    if not cand:
        sys.exit("ERROR: no transaction rows conform to the inferred date format.")

    # Keep all remaining candidates — transaction tables can be split into
    # several blocks (per card section / per page) with summary boxes between.
    txn_idx = cand
    txn_set = set(txn_idx)

    first, last = txn_idx[0], txn_idx[-1]
    # If the last transaction's amounts live on lines below its date line,
    # extend the table range to cover its trailing block.
    if not any(parse_amount(norm_token(w["text"])) for w in lines[last]):
        for j in range(last + 1, min(last + 7, len(lines))):
            if anchors[j]:
                break
            text = line_text(lines[j]).lower()
            if (OPENING_BAL_RE.search(text) or CLOSING_BAL_RE.search(text)
                    or is_summary_line(text)
                    or any(kw in text for kw in ("page ", "statement", "generated on"))):
                break
            last = j
    header_idx = find_header_row(lines, first)
    # Metadata can sit above the table AND below it (e.g. a trailing
    # "Closing Balance : ..." line), and marker rows like "Opening Balance B/F"
    # often sit between the column header and the first transaction row.
    # Trust only pages that carry transactions — trailing T&C pages contain
    # example figures next to the same labels ("balance carried forward...").
    max_txn_page = max(lines[i][0]["page"] for i in txn_idx)
    meta_zone = [ln for ln in lines[:first] + lines[last + 1:]
                 if ln[0]["page"] <= max_txn_page]

    raw = []  # (line, is_new_txn, anchor)
    for i in range(first, last + 1):
        ln = lines[i]
        if i in txn_set:
            raw.append([ln, True, anchors[i]])
        elif raw:
            # Dateless line inside the table: continuation of previous row —
            # unless it's a summary/furniture line (contains balance keywords).
            text = line_text(ln).lower()
            if OPENING_BAL_RE.search(text) or CLOSING_BAL_RE.search(text):
                raw.append([ln, None, None])  # marker row, keep for opening balance
            elif (any(kw in text for kw in ("page ", "statement", "generated on"))
                  or is_summary_line(text)          # totals/section boxes
                  or CARD_MASK_RE.search(text)      # per-card section subheaders
                  or re.search(r"\d\s?%", text)     # interest-rate notes
                  or re.search(r"\bgstin\b|\bhsn\b", text)):  # invoice furniture
                continue
            else:
                raw.append([ln, False, None])

    if debug:
        n_txn = sum(1 for _, t, _a in raw if t)
        n_cont = sum(1 for _, t, _a in raw if t is False)
        print(f"[debug] table region: lines {first}..{last}, "
              f"{n_txn} txn rows, {n_cont} continuation lines, header row idx: {header_idx}",
              file=sys.stderr)

    header_words = lines[header_idx] if header_idx is not None else None
    return meta_zone, raw, header_words, date_fmt, date_fmt2


def build_transactions(raw_rows, date_fmt, date_fmt2=None, period=None):
    """Assemble txn dicts: date(s), description words, amount tokens with x-positions."""
    row_fmts = [f for f in (date_fmt, date_fmt2, "%Y/%m/%d", "%Y-%m-%d") if f]

    def parse_row_date(datestr):
        for fmt in row_fmts[:2]:
            try:
                return datetime.strptime(datestr, fmt)
            except ValueError:
                pass
        return None

    def inject_year(d):
        # Yearless formats ("20MAY") parse to year 1900 — take the year from
        # the statement period (handles Dec/Jan statements spanning two years).
        if d.year != 1900 or not (period and period[0] and period[1]):
            return d
        for y in {period[0].year, period[1].year}:
            try:
                cand = d.replace(year=y)
            except ValueError:
                continue
            if period[0] <= cand <= period[1]:
                return cand
        return d.replace(year=period[1].year)

    def consume_date(words, pos):
        """Length of a date window at pos (in one of the statement's own formats)
        plus any trailing time tokens; 0 if none. Value-date columns share the
        statement's formats — date-like strings in narrations usually don't,
        and are kept."""
        for span in (3, 2, 1):
            if pos + span > len(words):
                continue
            cand = " ".join(norm_token(w["text"]) for w in words[pos:pos + span])
            if not DATE_TOKEN_RE.match(cand):
                continue
            parsed = False
            for fmt in row_fmts:
                try:
                    datetime.strptime(cand, fmt)
                    parsed = True
                    break
                except ValueError:
                    pass
            if not parsed:
                continue
            n = span
            while pos + n < len(words) and TIME_RE.match(norm_token(words[pos + n]["text"])):
                n += 1
            return n
        return 0

    # Multi-line block layouts: when a date line carries no amounts, the whole
    # block (all lines until the next transaction/marker) IS the transaction —
    # absorb those lines so its amounts and balance are parsed as one row.
    merged, i = [], 0
    rows = [list(r) for r in raw_rows]
    while i < len(rows):
        ln, kind, anchor = rows[i]
        if kind is True and not any(parse_amount(norm_token(w["text"])) for w in ln):
            block = list(ln)
            j = i + 1
            while j < len(rows) and rows[j][1] is not True:
                if rows[j][1] is False:
                    block += rows[j][0]
                else:
                    merged.append(rows[j])  # keep marker rows (page-boundary B/F)
                j += 1
            merged.append([block, True, anchor])
            i = j
        else:
            merged.append(rows[i])
            i += 1
    raw_rows = merged

    txns, continuations = [], []
    opening_from_marker = None
    for pos, (ln, is_new, anchor) in enumerate(raw_rows):
        if is_new is None:  # opening/closing marker row e.g. "Balance B/F ... 50,000.00"
            # The first "brought forward" marker in the table is the opening
            # balance (later ones are page-boundary repeats).
            if opening_from_marker is None and OPENING_BAL_RE.search(line_text(ln)):
                amts = [parse_amount(norm_token(w["text"])) for w in ln]
                amts = [a for a in amts if a]
                if amts:
                    opening_from_marker = amts[-1][0]
            continue
        if not is_new:
            continuations.append((pos, ln))
            continue
        start, span, datestr = anchor
        parsed_date = parse_row_date(datestr)
        if parsed_date is None:
            continue
        date = inject_year(parsed_date)
        # pre-date columns (serial no etc.); chart labels like "62%" overlap rows
        amounts, desc = [], [w["text"] for w in ln[:start] if not w["text"].endswith("%")]
        desc_x = []  # x-extent of description words (locates the desc column)
        pending_sign = 0  # from a standalone +/- marker before the amount
        i = start + span
        while i < len(ln) and TIME_RE.match(norm_token(ln[i]["text"])):
            i += 1  # "DATE & TIME" columns: drop the time part
        while i < len(ln):
            n = consume_date(ln, i)  # value-date column, wherever it sits
            if n:
                i += n
                continue
            w = ln[i]
            tok = norm_token(w["text"])
            nxt = norm_token(ln[i + 1]["text"]) if i + 1 < len(ln) else ""
            amt = parse_amount(tok)
            if amt:
                amounts.append({"value": amt[0], "sign": amt[1] or pending_sign,
                                "x1": w["x1"], "text": tok})
                pending_sign = 0
            elif tok in CURRENCY_TOKENS and parse_amount(nxt):
                pass  # currency glyph rendered as its own word before the amount
            elif tok in ("+", "-", "–"):
                # A +/- is a sign marker only when directly beside the amount
                # (or its currency glyph). Reward-point columns also print
                # "+ 5" / "- 15" before the amount — those are not signs.
                nxt2 = norm_token(ln[i + 2]["text"]) if i + 2 < len(ln) else ""
                if parse_amount(nxt) or (nxt in CURRENCY_TOKENS and parse_amount(nxt2)):
                    pending_sign = 1 if tok == "+" else -1
            elif re.match(r"^(Cr|Dr|CR|DR)$", tok):
                if amounts:  # standalone flag beside the amount cell
                    amounts[-1]["sign"] = 1 if tok.lower().startswith("c") else -1
            elif len(tok) == 1 and tok.isalpha():
                pass  # indicator-column glyphs (dots/bullets extract as a letter)
            else:
                desc.append(w["text"])
                desc_x += [w["x0"], w["x1"]]
            i += 1
        txns.append({"date": date, "desc": desc, "amounts": amounts,
                     "pos": pos, "page": ln[0]["page"], "top": ln[0]["top"],
                     "desc_x": desc_x})

    # Description column x-extent per page (tables can shift between pages).
    page_span = {}
    for t in txns:
        if t["desc_x"]:
            lo, hi = min(t["desc_x"]), max(t["desc_x"])
            ps = page_span.setdefault(t["page"], [lo, hi])
            ps[0], ps[1] = min(ps[0], lo), max(ps[1], hi)

    # Attach continuation lines to the vertically nearest transaction — wrapped
    # narrations can sit above OR below their dated row. Lines outside the
    # description column (marketing text, section titles) are dropped.
    for pos, ln in continuations:
        page, top, x0 = ln[0]["page"], ln[0]["top"], ln[0]["x0"]
        span = page_span.get(page)
        if not txns or (span and not (span[0] - 25 <= x0 <= span[1] + 25)):
            continue
        near = min(txns, key=lambda t: (0, abs(t["top"] - top)) if t["page"] == page
                   else (1, abs(t["pos"] - pos)))
        words = [w["text"] for w in ln
                 if not parse_amount(norm_token(w["text"]))
                 and norm_token(w["text"]) not in CURRENCY_TOKENS
                 and not (len(norm_token(w["text"])) == 1
                          and norm_token(w["text"]).isalpha())]
        if near["pos"] > pos:
            near["desc"] = words + near["desc"]
        else:
            near["desc"] += words
    # A row with no amounts anywhere is not a transaction (stray dated
    # summary/footer lines that slipped past the filters).
    txns = [t for t in txns if t["amounts"]]
    return txns, opening_from_marker


# ----------------------------------------------------------------------------
# Stage 6: column clustering + balance-chain oracle
# ----------------------------------------------------------------------------

def cluster_amount_columns(txns):
    """Cluster amount right-edges (amounts are right-aligned) into columns."""
    centers = []  # list of [x1, count]
    for t in txns:
        for a in t["amounts"]:
            for c in centers:
                if abs(c[0] - a["x1"]) <= COL_TOL:
                    c[0] = (c[0] * c[1] + a["x1"]) / (c[1] + 1)
                    c[1] += 1
                    a["col"] = id(c)
                    break
            else:
                c = [a["x1"], 1]
                centers.append(c)
                a["col"] = id(c)
    order = {id(c): rank for rank, c in enumerate(sorted(centers, key=lambda c: c[0]))}
    for t in txns:
        for a in t["amounts"]:
            a["col"] = order[a["col"]]
    return len(centers)


def run_balance_oracle(txns, n_cols, opening_balance, debug=False):
    """
    Try each column as 'running balance'. For the winning hypothesis, every row's
    balance delta must equal one of that row's other amounts. The delta's sign
    then labels the transaction credit(+) / debit(-). Returns (balance_col, score).
    """
    best_col, best_score = None, 0.0
    for col in range(n_cols):
        prev = opening_balance
        checked = validated = 0
        for t in txns:
            bal = next((a["value"] for a in t["amounts"] if a["col"] == col), None)
            others = [a["value"] for a in t["amounts"] if a["col"] != col]
            if bal is None:
                continue
            if prev is not None and others:
                checked += 1
                delta = bal - prev
                if any(abs(abs(delta) - v) < EPS for v in others):
                    validated += 1
            prev = bal
        score = validated / checked if checked else 0.0
        if debug:
            print(f"[debug] oracle: col {col} as balance -> {validated}/{checked} rows validate",
                  file=sys.stderr)
        if score > best_score:
            best_col, best_score = col, score
    return best_col, best_score


def assign_signs(txns, balance_col, opening_balance):
    """Give each txn a signed amount + running balance using the validated chain."""
    def chains(t, prev):
        bal = next((a["value"] for a in t["amounts"] if a["col"] == balance_col), None)
        others = [a for a in t["amounts"] if a["col"] != balance_col]
        if bal is None or prev is None or not others:
            return None
        delta = bal - prev
        match = next((a for a in others if abs(abs(delta) - a["value"]) < EPS), None)
        if not match:
            return None
        return bal, (match["value"] if delta >= 0 else -match["value"]), match["col"]

    def run(opening):
        prev = opening
        results, chain_breaks, votes = [], 0, {}
        for idx, t in enumerate(txns):
            bal = next((a["value"] for a in t["amounts"] if a["col"] == balance_col), None)
            others = [a for a in t["amounts"] if a["col"] != balance_col]
            hit = chains(t, prev)
            # Chain-outlier pruning: a row that breaks the chain while the NEXT
            # row chains directly from the previous balance is not a transaction
            # (summary boxes and ads sometimes carry a date and amounts).
            if hit is None and prev is not None and idx + 1 < len(txns) \
                    and chains(txns[idx + 1], prev):
                continue
            if hit:
                bal, amount, col = hit
                valid = True
                votes.setdefault(col, []).append(1 if amount >= 0 else -1)
            else:
                amount, valid = None, False
                if others:
                    a = max(others, key=lambda a: a["value"])
                    amount = a["value"] * (a["sign"] or 1)   # fall back to explicit Cr/Dr
                    chain_breaks += 1
            results.append({
                "date": t["date"], "description": " ".join(t["desc"]),
                "amount": amount, "balance": bal, "chain_valid": valid,
            })
            if bal is not None:
                prev = bal
        return results, chain_breaks, votes

    results, chain_breaks, votes = run(opening_balance)
    if opening_balance is None and txns:
        # No printed opening balance: derive it from the first row. Its sign
        # comes from an explicit Cr/Dr flag, or from the sign its amount
        # column consistently carried across the validated rows.
        t0 = txns[0]
        bal0 = next((a["value"] for a in t0["amounts"] if a["col"] == balance_col), None)
        for a in [x for x in t0["amounts"] if x["col"] != balance_col]:
            vs = votes.get(a["col"], [])
            sign = a["sign"] or (vs[0] if len(vs) >= 2 and abs(sum(vs)) == len(vs) else 0)
            if bal0 is not None and sign:
                r2, cb2, _ = run(round(bal0 - sign * a["value"], 2))
                if (sum(r["chain_valid"] for r in r2)
                        > sum(r["chain_valid"] for r in results)):
                    results, chain_breaks = r2, cb2
                    opening_balance = round(bal0 - sign * a["value"], 2)
                break
    return results, chain_breaks, opening_balance


def assign_signs_no_balance(txns, header_words, default_sign=1):
    """Fallback when no balance column exists (e.g. credit cards): explicit
    Cr/Dr or +/- flags, then header debit/credit columns, then the document
    default (on a credit card an unmarked row is a purchase = debit)."""
    debit_x = credit_x = None
    if header_words:
        for w in header_words:
            wl = w["text"].lower()
            if any(k in wl for k in ("debit", "withdraw", "paid out")):
                debit_x = w["x1"]
            if any(k in wl for k in ("credit", "deposit", "paid in")):
                credit_x = w["x1"]
    results = []
    for t in txns:
        amount, source = None, None
        for a in t["amounts"]:
            if a["sign"]:
                amount, source = a["value"] * a["sign"], "explicit"
                break
        if amount is None and t["amounts"]:
            a = t["amounts"][0]
            if debit_x is not None and abs(a["x1"] - debit_x) < 40:
                amount, source = -a["value"], "header"
            elif credit_x is not None and abs(a["x1"] - credit_x) < 40:
                amount, source = a["value"], "header"
            else:
                amount, source = a["value"] * default_sign, "default"
        results.append({"date": t["date"], "description": " ".join(t["desc"]),
                        "amount": amount, "balance": None, "chain_valid": False,
                        "sign_source": source})
    return results


# ----------------------------------------------------------------------------
# Stage 8b: summary-field harvesting (label vocabulary + geometric pairing)
# ----------------------------------------------------------------------------

SUMMARY_FIELD_LABELS = [
    # order irrelevant — overlapping matches resolved longest-first per line
    ("total_amount_due",       r"total\s+(?:amount\s+|payment\s+)?due\b|net\s+outstanding\s+balance"
                               r"|total\s+outstanding\b|outstanding\s+balance"),
    ("minimum_amount_due",     r"min(?:imum)?\s+(?:amount\s+|payment\s+)?due\b"),
    ("payment_due_date",       r"(?:payment\s+)?due\s+date"),
    ("credits_received",       r"\bcredits\b"),
    ("cash_advance",           r"cash\s+advances?\b"),
    ("fees_and_charges",       r"other\s+debit\s*&?\s*charges|fees\s*&?\s*(?:taxes|charges)"
                               r"|fees\s+and\s+charges"),
    ("credit_limit",           r"(?:total\s+)?credit\s+limit"),
    ("available_credit_limit", r"available\s+credit(?:\s+limit)?"),
    ("available_cash_limit",   r"available\s+cash(?:\s+limit)?"),
    ("cash_limit",             r"\bcash\s+limit"),
    ("finance_charges",        r"finance\s+charges"),
    ("previous_balance",       r"previous\s+(?:statement\s+)?(?:dues|balance)|opening\s+balance"),
    ("payments_received",      r"payments?\s*/?\s*credits?\b|payments?\s+received"
                               r"|\bpayments\b(?!\s+due)"),
    ("total_purchases",        r"purchases\s*/?\s*(?:debits?|charges)"
                               r"|total\s+purchases?(?:\s+outstanding)?|\bpurchases?\b"),
    ("total_withdrawals",      r"total\s+withdrawals?|total\s+debits?"),
    ("total_deposits",         r"total\s+deposits?|total\s+credits?"),
    ("interest_earned",        r"interest\s+(?:earned|paid|credited)|int\.?\s*pd"),
    ("reward_points_balance",  r"reward\s+points?(?:\s+(?:opening\s+)?balance)?"),
    ("reward_points_earned",   r"(?:points?|rewards?)\s+earned"),
    ("overlimit_amount",       r"over\s*limit"),
]

DATE_VALUED_FIELDS = {"payment_due_date"}


def dedouble(t):
    """Bold headings in some PDFs render by double-printing every glyph and
    extract as doubled letters ("DDUUEE DDAATTEE"). Collapse those."""
    if len(t) >= 6 and len(t) % 2 == 0 and t[0::2] == t[1::2]:
        return t[0::2]
    return t


def loose_amount(token):
    """Amounts in summary boxes often drop decimals ("C9,20,000", "2,864").
    Comma grouping or a currency prefix is required so years/ids don't match."""
    a = parse_amount(token)
    if a:
        return a[0]
    t = norm_token(token)
    m = re.match(r"^(?:[₹$€£C`]|Rs\.?|INR)?\s?(\d{1,3}(?:,\d{2,3})+)$", t)
    if m:
        return float(m.group(1).replace(",", ""))
    return None


def harvest_summary_fields(lines, txn_line_ids, max_txn_page):
    """Sweep non-transaction lines for known financial labels and pair each
    with its value: to the right on the same line, else the x-aligned value
    on a nearby line below (the two-row summary-box pattern)."""
    fields = {}
    idx = [(li, ln) for li, ln in enumerate(lines)
           if id(ln) not in txn_line_ids and ln[0]["page"] <= max_txn_page]

    DUES_FIELDS = {"total_amount_due", "previous_balance", "minimum_amount_due"}

    def dues_signed(ln2, w, value, field):
        """Card dues carry Dr/Cr direction: Dr = owed (+), Cr = credit (−)."""
        if field not in DUES_FIELDS:
            return value
        a = parse_amount(norm_token(w["text"]))
        s = a[1] if a and a[1] else 0
        if not s:
            k = ln2.index(w)
            if k + 1 < len(ln2):
                t = norm_token(ln2[k + 1]["text"]).lower()
                s = 1 if t == "cr" else -1 if t == "dr" else 0
        return -value if s == 1 else value

    def value_token(w, want_date):
        if want_date:
            return None  # dates span words; handled window-wise in value_below
        return loose_amount(w["text"])

    def value_right(ln, lx1, want_date, field):
        right = [w for w in ln if w["x0"] >= lx1 - 2]
        if want_date:
            d = [x for x in find_dates_in_words(right, DATE_FORMATS) if x.year > 1990]
            return d[0] if d else None
        for w in right:
            v = loose_amount(w["text"])
            if v is not None:
                return dues_signed(ln, w, v, field)
        return None

    def value_below(pos, page, top, lx0, lx1, want_date, field):
        cx = (lx0 + lx1) / 2
        for lj, ln2 in idx:
            if ln2[0]["page"] != page or not (0 < ln2[0]["top"] - top <= 80):
                continue
            in_window = [w for w in ln2
                         if lx0 - 25 <= (w["x0"] + w["x1"]) / 2 <= lx1 + 45]
            if want_date:
                d = [x for x in find_dates_in_words(in_window, DATE_FORMATS)
                     if x.year > 1990]
                if d:
                    return d[0]
                continue
            best = None
            for w in in_window:
                wcx = (w["x0"] + w["x1"]) / 2
                v = value_token(w, want_date)
                if v is not None and (best is None or abs(wcx - cx) < best[0]):
                    best = (abs(wcx - cx), v, w)
            if best:
                return dues_signed(ln2, best[2], best[1], field)
        return None

    def labelish(ln):
        # Summary labels live on short lines — or on long but Title-Case rows
        # ("Previous Balance - Payments - Credits + Purchase ..."); prose
        # sentences that merely mention a label are lowercase-heavy.
        if len(ln) <= 20:
            return True
        alpha = [w["text"] for w in ln if w["text"][:1].isalpha()]
        return bool(alpha) and sum(t[:1].isupper() for t in alpha) / len(alpha) >= 0.6

    for li, ln in idx:
        if not labelish(ln):
            continue
        text, spans = "", []
        for w in ln:
            if text:
                text += " "
            token = dedouble(w["text"])  # bold headings extract letter-doubled
            spans.append((len(text), len(text) + len(token), w))
            text += token
        tl = text.lower()
        matches = []
        for field, pat in SUMMARY_FIELD_LABELS:
            for m in re.finditer(pat, tl):
                matches.append((m.end() - m.start(), field, m.start(), m.end()))
        matches.sort(reverse=True)  # longest label wins overlapping spans
        chosen = []
        for _len, field, s, e in matches:
            if any(not (e <= s2 or s >= e2) for _f, s2, e2 in chosen):
                continue
            chosen.append((field, s, e))
        for field, s, e in chosen:
            if field in fields:
                continue  # first (topmost) occurrence wins
            lw = [w for ws, we, w in spans if ws < e and we > s]
            lx0, lx1 = lw[0]["x0"], lw[-1]["x1"]
            want_date = field in DATE_VALUED_FIELDS
            val = value_right(ln, lx1, want_date, field)
            if val is None:
                val = value_below(li, ln[0]["page"], ln[0]["top"], lx0, lx1,
                                  want_date, field)
            if val is not None:
                fields[field] = val
    return fields


# ----------------------------------------------------------------------------
# Stage 8: header metadata inference
# ----------------------------------------------------------------------------

def extract_metadata(pre_table_lines, date_fmt, debug=False):
    meta = {"bank": None, "account_number": None, "ifsc": None, "holder_name": None,
            "period_start": None, "period_end": None,
            "opening_balance": None, "closing_balance": None}
    all_dates_fmt = date_fmt

    bank_fallback = None
    for i, ln in enumerate(pre_table_lines):
        text = line_text(ln)
        if meta["bank"] is None and BANK_BRAND_RE.search(text) \
                and not re.search(r"[@]|www\.|https?:", text):
            if len(ln) <= 6:  # short masthead line, not a sentence mentioning a bank
                meta["bank"] = text.strip()
            elif bank_fallback is None:
                bank_fallback = text.strip()
        m = ACCOUNT_LABEL_RE.search(text)
        if m and meta["account_number"] is None:
            meta["account_number"] = m.group(1)
        if meta["account_number"] is None:
            m = CARD_MASK_RE.search(text)
            if m and re.search(r"[Xx*]", m.group(1)):  # masked card, not 4 number columns
                meta["account_number"] = m.group(1)
        m = IFSC_RE.search(text)
        if m and meta["ifsc"] is None:
            meta["ifsc"] = m.group(1)
        m = NAME_LABEL_RE.search(text)
        if (m and meta["holder_name"] is None
                and not re.search(r"(?:branch|bank|scheme|product|nominee)\s+name", text, re.I)):
            meta["holder_name"] = m.group(1).strip()
        if meta["period_start"] is None and (
                PERIOD_LINE_RE.search(text)
                or re.search(r"\b(to|till|through)\b", text, re.I)):
            fmts = ([all_dates_fmt] if all_dates_fmt else []) + DATE_FORMATS
            found = [d for d in find_dates_in_words(ln, fmts) if d.year > 1990]
            if len(found) < 2 and PERIOD_LINE_RE.search(text):
                # Label row with the date range on the value row below. That
                # row can hold other columns' dates too (due date etc.) — take
                # the first adjacent ascending pair that spans a plausible cycle.
                for ln2 in pre_table_lines[i + 1:i + 3]:
                    ds = [d for d in find_dates_in_words(ln2, fmts) if d.year > 1990]
                    pair = next(((a, b) for a, b in zip(ds, ds[1:])
                                 if 5 <= (b - a).days <= 400), None)
                    if pair:
                        found = list(pair)
                        break
            if len(found) >= 2 and (max(found) - min(found)).days <= 400:
                meta["period_start"], meta["period_end"] = min(found), max(found)
        # Balance labels must be on short summary lines — T&C sentences also
        # say "opening balance" but with example figures.
        if OPENING_BAL_RE.search(text) and meta["opening_balance"] is None and len(ln) <= 8:
            amts = [parse_amount(norm_token(w["text"])) for w in ln]
            amts = [a for a in amts if a]
            if amts:
                meta["opening_balance"] = amts[-1][0]
        if CLOSING_BAL_RE.search(text) and meta["closing_balance"] is None and len(ln) <= 8:
            amts = [parse_amount(norm_token(w["text"])) for w in ln]
            amts = [a for a in amts if a]
            if amts:
                meta["closing_balance"] = amts[-1][0]
    if meta["bank"] is None:
        meta["bank"] = bank_fallback
    return meta


# ----------------------------------------------------------------------------
# Stage 9: validation report + output
# ----------------------------------------------------------------------------

def main():
    ap = argparse.ArgumentParser(description="Generic (template-free, AI-free) bank statement PDF parser")
    ap.add_argument("pdf", help="path to the statement PDF")
    ap.add_argument("--password", help="password for encrypted PDFs", default=None)
    ap.add_argument("--json", action="store_true", help="emit machine-readable JSON")
    ap.add_argument("--debug", action="store_true", help="print inference decisions to stderr")
    args = ap.parse_args()

    lines = extract_lines(args.pdf, args.password)
    pre_table, raw_rows, header_words, date_fmt, date_fmt2 = collect_rows(lines, debug=args.debug)

    meta = extract_metadata(pre_table, date_fmt, debug=args.debug)
    if meta["account_number"] is None:
        # Labeled account numbers can sit inside the table zone (per-account
        # section headers in composite statements) — labels make this safe.
        for ln in lines:
            m = ACCOUNT_LABEL_RE.search(line_text(ln))
            if m:
                meta["account_number"] = m.group(1)
                break
    txns, opening_marker = build_transactions(
        raw_rows, date_fmt, date_fmt2,
        period=(meta["period_start"], meta["period_end"]))
    if opening_marker is not None and meta["opening_balance"] is None:
        meta["opening_balance"] = opening_marker

    n_cols = cluster_amount_columns(txns)
    balance_col, oracle_score = run_balance_oracle(
        txns, n_cols, meta["opening_balance"], debug=args.debug)

    full_text = " ".join(line_text(ln) for ln in lines).lower()
    is_credit_card = "credit card" in full_text or "credit limit" in full_text

    txn_line_ids = {id(ln) for ln, is_new, _a in raw_rows if is_new}
    max_txn_page = max((ln[0]["page"] for ln, is_new, _a in raw_rows if is_new), default=0)
    summary_fields = harvest_summary_fields(lines, txn_line_ids, max_txn_page)

    if balance_col is not None and oracle_score >= 0.6:
        results, chain_breaks, opening_used = assign_signs(
            txns, balance_col, meta["opening_balance"])
        if meta["opening_balance"] is None:
            meta["opening_balance"] = opening_used
        mode = "balance-chain"
    else:
        results = assign_signs_no_balance(txns, header_words,
                                          default_sign=-1 if is_credit_card else 1)
        chain_breaks, mode = len(results), "heuristic (no balance column validated)"

        # No running-balance column, but opening and closing figures are known
        # (labels, or the card summary box): reconcile opening ± net == closing.
        # This confirms the sign guesses — or flips them if only the flipped
        # assignment balances — and lets us derive a per-row running balance.
        opening = meta["opening_balance"]
        if opening is None:
            opening = summary_fields.get("previous_balance")
        closing = meta["closing_balance"]
        if closing is None and is_credit_card:
            closing = summary_fields.get("total_amount_due")
        if opening is not None and closing is not None and results:
            tol = 0.51  # printed card dues are often rounded to the rupee
            direction = -1 if is_credit_card else 1  # card dues grow with debits
            flipped = [dict(r, amount=-r["amount"])
                       if r.get("sign_source") == "default" and r["amount"] is not None
                       else dict(r) for r in results]
            for cand in (results, flipped):
                net = sum(r["amount"] or 0 for r in cand)
                if abs(opening + direction * net - closing) <= tol:
                    results = cand
                    bal = opening
                    for r in results:
                        bal += direction * (r["amount"] or 0)
                        r["balance"] = round(bal, 2)
                        # the chain is anchored at opening and lands on closing,
                        # so every row is part of a validated chain
                        r["chain_valid"] = True
                    mode = "opening/closing reconciliation"
                    meta["opening_balance"], meta["closing_balance"] = opening, closing
                    break

    # ---- validation summary ----
    credits = sum(r["amount"] for r in results if r["amount"] and r["amount"] > 0)
    debits = sum(-r["amount"] for r in results if r["amount"] and r["amount"] < 0)
    valid_rows = sum(1 for r in results if r["chain_valid"])
    closing_derived = results[-1]["balance"] if results and results[-1]["balance"] is not None else None
    # Validate against the statement's own printed closing balance when present
    # (an independent figure); fall back to the last running-balance value.
    closing_target = meta["closing_balance"] if meta["closing_balance"] is not None else closing_derived
    checksum_ok = None
    if meta["opening_balance"] is not None and closing_target is not None:
        checksum_ok = abs(meta["opening_balance"] + credits - debits - closing_target) < EPS
        if checksum_ok is False and is_credit_card:
            # Card statements chain the other way (dues grow with spends), and
            # print the due rounded to the rupee — hence the wider tolerance.
            checksum_ok = abs(meta["opening_balance"] + debits - credits - closing_target) <= 0.51

    # Statement-level cross-check for balance-less statements (credit cards):
    # the statement prints total purchases/payments somewhere — if our parsed
    # totals appear verbatim outside the rows we summed, the parse is
    # independently confirmed.
    summary_values = set()
    for ln in lines:
        if id(ln) in txn_line_ids:
            continue
        for w in ln:
            a = parse_amount(norm_token(w["text"]))
            if a:
                summary_values.add(a[0])

    debit_rows = [r for r in results if r["amount"] is not None and r["amount"] < 0]
    credit_rows = [r for r in results if r["amount"] is not None and r["amount"] > 0]
    merchant_spend = {}
    for r in debit_rows:
        key = " ".join(r["description"].split()[:3]) or "(no description)"
        merchant_spend[key] = merchant_spend.get(key, 0.0) - r["amount"]
    top_merchants = sorted(merchant_spend.items(), key=lambda kv: -kv[1])[:5]
    n_days = ((results[-1]["date"] - results[0]["date"]).days + 1) if results else 0
    derived = {
        # A validated balance chain used bank semantics (balance grows with
        # credits) — composite statements mention "credit limit" without being
        # card statements, so the chain is the stronger signal.
        "statement_type": ("bank_account" if mode == "balance-chain"
                           else "credit_card" if is_credit_card else "bank_account"),
        "transaction_count": len(results),
        "debit_count": len(debit_rows),
        "credit_count": len(credit_rows),
        "largest_debit": (min(debit_rows, key=lambda r: r["amount"]) if debit_rows else None),
        "largest_credit": (max(credit_rows, key=lambda r: r["amount"]) if credit_rows else None),
        "avg_debit": round(sum(-r["amount"] for r in debit_rows) / len(debit_rows), 2)
                     if debit_rows else None,
        "avg_daily_spend": round(sum(-r["amount"] for r in debit_rows) / n_days, 2)
                           if debit_rows and n_days else None,
        "active_days": len({r["date"].date() for r in results}),
        "top_merchants": top_merchants,
    }
    cross_debits = any(abs(v - debits) < EPS for v in summary_values) if debits else None
    cross_credits = any(abs(v - credits) < EPS for v in summary_values) if credits else None

    # Labeled credit-card equations against the harvested summary fields:
    #   purchases == sum(debits); payments == sum(credits);
    #   previous balance + finance charges + purchases − payments == total due.
    TOL = 0.51  # card statements round printed figures to the rupee
    card_checks = {}
    if is_credit_card:
        if "total_purchases" in summary_fields:
            card_checks["purchases == sum(debits)"] = \
                abs(summary_fields["total_purchases"] - debits) <= TOL
        if "payments_received" in summary_fields:
            # some banks split payments and other credits into two figures
            expected = summary_fields["payments_received"] \
                + summary_fields.get("credits_received", 0.0)
            card_checks["payments == sum(credits)"] = abs(expected - credits) <= TOL
        prev = summary_fields.get("previous_balance", meta["opening_balance"])
        due = summary_fields.get("total_amount_due")
        if prev is not None and due is not None:
            fc = summary_fields.get("finance_charges") or 0.0
            card_checks["prev + charges + spends - payments == due"] = \
                abs(prev + fc + debits - credits - due) <= TOL
    if meta["period_start"] is None and results:
        dates = [r["date"] for r in results]
        meta["period_start"], meta["period_end"] = min(dates), max(dates)
    if meta["closing_balance"] is None:
        meta["closing_balance"] = closing_derived

    def _ser(v):
        return v.strftime("%Y-%m-%d") if isinstance(v, datetime) else v

    report = {
        "metadata": {**{k: _ser(v) for k, v in meta.items()},
                     "statement_type": derived["statement_type"]},
        "summary_fields": {k: _ser(v) for k, v in summary_fields.items()},
        "derived": {
            **{k: v for k, v in derived.items()
               if k not in ("largest_debit", "largest_credit", "top_merchants")},
            "largest_debit": ({"date": derived["largest_debit"]["date"].strftime("%Y-%m-%d"),
                               "amount": derived["largest_debit"]["amount"],
                               "description": derived["largest_debit"]["description"]}
                              if derived["largest_debit"] else None),
            "largest_credit": ({"date": derived["largest_credit"]["date"].strftime("%Y-%m-%d"),
                                "amount": derived["largest_credit"]["amount"],
                                "description": derived["largest_credit"]["description"]}
                               if derived["largest_credit"] else None),
            "top_merchants": [{"merchant": m, "spend": round(v, 2)}
                              for m, v in derived["top_merchants"]],
        },
        "parse": {
            "mode": mode,
            "transactions": len(results),
            "rows_chain_validated": valid_rows,
            "chain_validation_pct": round(100 * valid_rows / len(results), 1) if results else 0,
            "total_credits": round(credits, 2),
            "total_debits": round(debits, 2),
            "checksum_opening_plus_net_equals_closing": checksum_ok,
            "totals_found_in_statement_summary": {"debits": cross_debits,
                                                  "credits": cross_credits},
            "card_checks": card_checks,
        },
        "transactions": [
            {"date": r["date"].strftime("%Y-%m-%d"), "description": r["description"],
             "amount": r["amount"], "balance": r["balance"], "chain_valid": r["chain_valid"]}
            for r in results
        ],
    }

    if args.json:
        print(json.dumps(report, indent=2))
        return

    # ---- human-readable output ----
    print("=" * 78)
    print("STATEMENT METADATA (all inferred)")
    print("=" * 78)
    for k, v in report["metadata"].items():
        print(f"  {k:18} {v if v is not None else '-- not found --'}")
    print()
    if report["summary_fields"]:
        print("=" * 78)
        print("SUMMARY FIELDS (label-harvested)")
        print("=" * 78)
        for k, v in report["summary_fields"].items():
            print(f"  {k:24} {v:,.2f}" if isinstance(v, float) else f"  {k:24} {v}")
        print()
    d = report["derived"]
    print("=" * 78)
    print("DERIVED SUMMARY")
    print("=" * 78)
    print(f"  statement type     {d['statement_type']}")
    print(f"  transactions       {d['transaction_count']} "
          f"({d['debit_count']} debits, {d['credit_count']} credits) "
          f"across {d['active_days']} active days")
    if d["largest_debit"]:
        ld = d["largest_debit"]
        print(f"  largest debit      {ld['amount']:,.2f} on {ld['date']} — {ld['description']}")
    if d["largest_credit"]:
        lc = d["largest_credit"]
        print(f"  largest credit     {lc['amount']:+,.2f} on {lc['date']} — {lc['description']}")
    if d["avg_debit"] is not None:
        print(f"  avg debit          {d['avg_debit']:,.2f}   avg daily spend {d['avg_daily_spend']:,.2f}")
    if d["top_merchants"]:
        print("  top merchants      " + "; ".join(
            f"{m['merchant']} ({m['spend']:,.0f})" for m in d["top_merchants"]))
    print()
    print("=" * 78)
    print(f"TRANSACTIONS ({len(results)})   mode: {mode}")
    print("=" * 78)
    print(f"  {'DATE':<12}{'AMOUNT':>14}{'BALANCE':>14}  {'OK':<3} DESCRIPTION")
    for r in report["transactions"]:
        amt = f"{r['amount']:+,.2f}" if r["amount"] is not None else "?"
        bal = f"{r['balance']:,.2f}" if r["balance"] is not None else "-"
        ok = "✓" if r["chain_valid"] else "·"
        print(f"  {r['date']:<12}{amt:>14}{bal:>14}  {ok:<3} {r['description']}")
    print()
    p = report["parse"]
    print("=" * 78)
    print("VALIDATION")
    print("=" * 78)
    chain_label = ("rows on reconciled opening->closing chain"
                   if mode.startswith("opening/closing")
                   else "rows validated by balance chain")
    print(f"  {chain_label:31} : {p['rows_chain_validated']}/{p['transactions']}"
          f" ({p['chain_validation_pct']}%)")
    print(f"  total credits                   : {p['total_credits']:,.2f}")
    print(f"  total debits                    : {p['total_debits']:,.2f}")
    cs = p["checksum_opening_plus_net_equals_closing"]
    print(f"  opening + net == closing        : "
          f"{'PASS ✓' if cs else 'FAIL ✗' if cs is False else 'n/a (missing opening/closing)'}")
    fmt_cross = lambda v: "FOUND ✓" if v else "not found" if v is False else "n/a"
    print(f"  totals in statement summary     : debits {fmt_cross(cross_debits)}, "
          f"credits {fmt_cross(cross_credits)}")
    for label, ok in card_checks.items():
        print(f"  card: {label:25} : {'PASS ✓' if ok else 'FAIL ✗'}")
    cross_ok = bool(cross_debits) and (cross_credits is not False)
    card_ok = (card_checks.get("prev + charges + spends - payments == due")
               or (card_checks.get("purchases == sum(debits)")
                   and card_checks.get("payments == sum(credits)")))
    verdict = ("AUTO-INGEST" if (cs and p["chain_validation_pct"] > 95)
                                 or (cs and mode.startswith("opening/closing"))
                                 or (cs is None and (cross_ok or card_ok))
               else "NEEDS REVIEW" if p["chain_validation_pct"] > 50
                                      or cross_debits or cross_credits or card_ok
               else "REJECT")
    print(f"  verdict                         : {verdict}")


if __name__ == "__main__":
    main()
