package com.financeos.statement.parser;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class TransactionBuilder {
    private static final Set<String> CR_DR_TOKENS = Set.of("Cr", "Dr", "CR", "DR");

    private TransactionBuilder() {
    }

    static TxnBuild build(List<RawRow> rawRows, String dateFmt, String dateFmt2,
                           LocalDate periodStart, LocalDate periodEnd, Line headerWords) {
        double[] refZone = refZone(headerWords);
        List<String> rowFmts = new ArrayList<>();
        for (String f : new String[]{dateFmt, dateFmt2, "%Y/%m/%d", "%Y-%m-%d"}) {
            if (f != null) {
                rowFmts.add(f);
            }
        }

        // Multi-line block layouts: when a date line carries no amounts, the whole
        // block (all lines until the next transaction/marker) IS the transaction —
        // absorb those lines so its amounts and balance are parsed as one row.
        List<RawRow> merged = new ArrayList<>();
        int mi = 0;
        while (mi < rawRows.size()) {
            RawRow r = rawRows.get(mi);
            if (Boolean.TRUE.equals(r.isNewTxn()) && r.line().words().stream()
                    .noneMatch(w -> Tokens.parseAmount(Tokens.norm(w.text())) != null)) {
                List<Word> block = new ArrayList<>(r.line().words());
                int mj = mi + 1;
                while (mj < rawRows.size() && !Boolean.TRUE.equals(rawRows.get(mj).isNewTxn())) {
                    RawRow rj = rawRows.get(mj);
                    if (Boolean.FALSE.equals(rj.isNewTxn())) {
                        block.addAll(rj.line().words());
                    } else {
                        merged.add(rj); // keep marker rows (page-boundary B/F)
                    }
                    mj++;
                }
                merged.add(new RawRow(new Line(block), true, r.anchor()));
                mi = mj;
            } else {
                merged.add(r);
                mi++;
            }
        }

        List<TxnDraft> txns = new ArrayList<>();
        List<Map.Entry<Integer, Line>> continuations = new ArrayList<>();
        Double openingFromMarker = null;

        for (int pos = 0; pos < merged.size(); pos++) {
            RawRow rr = merged.get(pos);
            Boolean isNew = rr.isNewTxn();
            Line ln = rr.line();
            if (isNew == null) { // opening/closing marker row e.g. "Balance B/F ... 50,000.00"
                // The first "brought forward" marker in the table is the opening
                // balance (later ones are page-boundary repeats).
                if (openingFromMarker == null && Tokens.OPENING_BAL.matcher(ln.text()).find()) {
                    Double last = null;
                    for (Word w : ln.words()) {
                        Amount a = Tokens.parseAmount(Tokens.norm(w.text()));
                        if (a != null) {
                            last = a.value();
                        }
                    }
                    if (last != null) {
                        openingFromMarker = last;
                    }
                }
                continue;
            }
            if (!isNew) {
                continuations.add(Map.entry(pos, ln));
                continue;
            }
            DateAnchor anchor = rr.anchor();
            int start = anchor.start();
            int span = anchor.span();
            LocalDate parsedDate = parseRowDate(anchor.text(), rowFmts);
            if (parsedDate == null) {
                continue;
            }
            LocalDate date = injectYear(parsedDate, periodStart, periodEnd);

            List<Word> words = ln.words();
            // pre-date columns (serial no etc.); chart labels like "62%" overlap rows
            List<String> desc = new ArrayList<>();
            for (int k = 0; k < Math.min(start, words.size()); k++) {
                String t = words.get(k).text();
                if (!t.endsWith("%")) {
                    desc.add(t);
                }
            }
            List<Double> descX = new ArrayList<>(); // x-extent of description words (locates the desc column)
            List<AmountCell> amounts = new ArrayList<>();
            int pendingSign = 0; // from a standalone +/- marker before the amount
            int ci = start + span;
            while (ci < words.size() && Tokens.isTimeToken(Tokens.norm(words.get(ci).text()))) {
                ci++; // "DATE & TIME" columns: drop the time part
            }
            while (ci < words.size()) {
                int n = consumeDate(words, ci, rowFmts); // value-date column, wherever it sits
                if (n > 0) {
                    ci += n;
                    continue;
                }
                Word w = words.get(ci);
                // Ref/cheque column values are not stored — skip them before amount
                // parsing too, or a numeric cheque number becomes an amount cell.
                if (refZone != null) {
                    double center = (w.x0() + w.x1()) / 2;
                    if (refZone[0] - 25 <= center && center <= refZone[1] + 25) {
                        ci++;
                        continue;
                    }
                }
                String tok = Tokens.norm(w.text());
                String nxt = ci + 1 < words.size() ? Tokens.norm(words.get(ci + 1).text()) : "";
                Amount amt = Tokens.parseAmount(tok);
                if (amt != null) {
                    amounts.add(new AmountCell(amt.value(), amt.sign() != 0 ? amt.sign() : pendingSign, w.x1(), tok));
                    pendingSign = 0;
                } else if (Tokens.isCurrencyToken(tok) && Tokens.parseAmount(nxt) != null) {
                    // currency glyph rendered as its own word before the amount
                } else if (tok.equals("+") || tok.equals("-") || tok.equals("–")) {
                    // A +/- is a sign marker only when directly beside the amount
                    // (or its currency glyph). Reward-point columns also print
                    // "+ 5" / "- 15" before the amount — those are not signs.
                    String nxt2 = ci + 2 < words.size() ? Tokens.norm(words.get(ci + 2).text()) : "";
                    if (Tokens.parseAmount(nxt) != null || (Tokens.isCurrencyToken(nxt) && Tokens.parseAmount(nxt2) != null)) {
                        pendingSign = tok.equals("+") ? 1 : -1;
                    }
                } else if (CR_DR_TOKENS.contains(tok)) {
                    if (!amounts.isEmpty()) { // standalone flag beside the amount cell
                        amounts.get(amounts.size() - 1).sign = tok.toLowerCase(Locale.ROOT).startsWith("c") ? 1 : -1;
                    }
                } else if (tok.length() == 1 && Character.isLetter(tok.charAt(0))) {
                    // indicator-column glyphs (dots/bullets extract as a letter)
                } else {
                    desc.add(w.text());
                    descX.add(w.x0());
                    descX.add(w.x1());
                }
                ci++;
            }
            txns.add(new TxnDraft(date, desc, amounts, pos, ln.page(), ln.top(), descX));
        }

        // Description column x-extent per page (tables can shift between pages).
        Map<Integer, double[]> pageSpan = new LinkedHashMap<>();
        for (TxnDraft t : txns) {
            if (!t.descX.isEmpty()) {
                double lo = Double.POSITIVE_INFINITY;
                double hi = Double.NEGATIVE_INFINITY;
                for (double x : t.descX) {
                    lo = Math.min(lo, x);
                    hi = Math.max(hi, x);
                }
                double[] ps = pageSpan.get(t.page);
                if (ps == null) {
                    pageSpan.put(t.page, new double[]{lo, hi});
                } else {
                    ps[0] = Math.min(ps[0], lo);
                    ps[1] = Math.max(ps[1], hi);
                }
            }
        }

        // Attach continuation lines to the vertically nearest transaction — wrapped
        // narrations can sit above OR below their dated row. Lines outside the
        // description column (marketing text, section titles) are dropped.
        for (Map.Entry<Integer, Line> entry : continuations) {
            int pos = entry.getKey();
            Line ln = entry.getValue();
            int page = ln.page();
            double top = ln.top();
            double x0 = ln.words().get(0).x0();
            double[] span = pageSpan.get(page);
            if (txns.isEmpty() || (span != null && !(span[0] - 25 <= x0 && x0 <= span[1] + 25))) {
                continue;
            }
            TxnDraft near = null;
            int bestTier = Integer.MAX_VALUE;
            double bestVal = Double.POSITIVE_INFINITY;
            for (TxnDraft t : txns) {
                int tier;
                double val;
                if (t.page == page) {
                    tier = 0;
                    val = Math.abs(t.top - top);
                } else {
                    tier = 1;
                    val = Math.abs(t.pos - pos);
                }
                if (tier < bestTier || (tier == bestTier && val < bestVal)) {
                    bestTier = tier;
                    bestVal = val;
                    near = t;
                }
            }
            List<String> words = new ArrayList<>();
            for (Word w : ln.words()) {
                String norm = Tokens.norm(w.text());
                if (Tokens.parseAmount(norm) != null
                        || Tokens.isCurrencyToken(norm)
                        || (norm.length() == 1 && Character.isLetter(norm.charAt(0)))) {
                    continue;
                }
                words.add(w.text());
            }
            if (near.pos > pos) {
                near.desc.addAll(0, words);
            } else {
                near.desc.addAll(words);
            }
        }
        // A row with no amounts anywhere is not a transaction (stray dated
        // summary/footer lines that slipped past the filters).
        txns.removeIf(t -> t.amounts.isEmpty());
        return new TxnBuild(txns, openingFromMarker);
    }

    private static double[] refZone(Line headerWords) {
        if (headerWords == null) {
            return null;
        }
        double lo = Double.POSITIVE_INFINITY;
        double hi = Double.NEGATIVE_INFINITY;
        for (Word w : headerWords.words()) {
            String wl = w.text().toLowerCase(Locale.ROOT);
            if (wl.contains("chq") || wl.contains("cheque") || wl.contains("ref") || wl.contains("utr")) {
                lo = Math.min(lo, w.x0());
                hi = Math.max(hi, w.x1());
            }
        }
        return lo <= hi ? new double[]{lo, hi} : null;
    }

    private static LocalDate parseRowDate(String datestr, List<String> rowFmts) {
        int limit = Math.min(2, rowFmts.size());
        for (int i = 0; i < limit; i++) {
            LocalDate d = Dates.parse(datestr, rowFmts.get(i));
            if (d != null) {
                return d;
            }
        }
        return null;
    }

    // Yearless formats ("20MAY") parse to year 1900 — take the year from
    // the statement period (handles Dec/Jan statements spanning two years).
    private static LocalDate injectYear(LocalDate d, LocalDate periodStart, LocalDate periodEnd) {
        if (d.getYear() != 1900 || periodStart == null || periodEnd == null) {
            return d;
        }
        Set<Integer> years = new LinkedHashSet<>();
        years.add(periodStart.getYear());
        years.add(periodEnd.getYear());
        for (int y : years) {
            LocalDate cand;
            try {
                cand = d.withYear(y);
            } catch (DateTimeException e) {
                continue;
            }
            if (!cand.isBefore(periodStart) && !cand.isAfter(periodEnd)) {
                return cand;
            }
        }
        return d.withYear(periodEnd.getYear());
    }

    // Length of a date window at pos (in one of the statement's own formats)
    // plus any trailing time tokens; 0 if none. Value-date columns share the
    // statement's formats — date-like strings in narrations usually don't,
    // and are kept.
    private static int consumeDate(List<Word> words, int pos, List<String> rowFmts) {
        for (int span : new int[]{3, 2, 1}) {
            if (pos + span > words.size()) {
                continue;
            }
            StringBuilder sb = new StringBuilder();
            for (int k = pos; k < pos + span; k++) {
                if (k > pos) {
                    sb.append(' ');
                }
                sb.append(Tokens.norm(words.get(k).text()));
            }
            String cand = sb.toString();
            if (!Tokens.isDateToken(cand)) {
                continue;
            }
            boolean parsed = false;
            for (String fmt : rowFmts) {
                if (Dates.parse(cand, fmt) != null) {
                    parsed = true;
                    break;
                }
            }
            if (!parsed) {
                continue;
            }
            int n = span;
            while (pos + n < words.size() && Tokens.isTimeToken(Tokens.norm(words.get(pos + n).text()))) {
                n++;
            }
            return n;
        }
        return 0;
    }
}
