package com.financeos.statement.parser;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.regex.Pattern;

final class TransactionBuilder {
    private static final Set<String> CR_DR_TOKENS = Set.of("Cr", "Dr", "CR", "DR");
    private static final Pattern PRINT_COUNTER = Pattern.compile("\\d+/\\d+");
    // Widest gap between two words of one narration (a space is ~2.5pt at 9pt).
    private static final double TEXT_GAP = 5;
    // heading of a cashback column sits right of its cells (Axis: cell centers sit 1.5pt or more left of the heading)
    private static final double CASHBACK_LEFT_PAD = 5;
    // longest vertical gap at which an undated line still wraps a row's narration;
    // a footer block ("CASHBACK DETAILS") further down is not part of it
    private static final double MAX_CONTINUATION_GAP = 25;
    // a date further right than this from its page's date column is inside the narration
    private static final double DATE_COL_TOL = 15;
    // how much wider (pt) the gap between two blocks is than any line gap inside one
    private static final double BLOCK_GAP = 2;
    // Row gaps that must agree before narration is trusted to sit below rows: at least
    // MIN_SIDE_VOTES, SIDE_LEAD times those that split right after the row, and SIDE_SHARE of
    // all clear gaps (a dropped or odd line now and then splits a block elsewhere).
    private static final int MIN_SIDE_VOTES = 3;
    private static final double SIDE_LEAD = 4;
    private static final double SIDE_SHARE = 0.6;
    private static final Pattern SPLIT_HEAD = Pattern.compile("\\d{1,3}");
    private static final Pattern SPLIT_TAIL = Pattern.compile(",\\d{2,3}(?:,\\d{2,3})*\\.\\d{1,2}");

    private record Attachment(TxnDraft row, int pos, int page, double top, List<String> words) {
    }

    private TransactionBuilder() {
    }

    static TxnBuild build(List<RawRow> rawRows, String dateFmt, String dateFmt2,
                           LocalDate periodStart, LocalDate periodEnd, Line headerWords,
                           NavigableMap<Integer, Line> pageHeaders) {
        rawRows = rejoinSplitAmounts(rawRows);
        double[] refZone = refZone(headerWords);
        Map<Integer, List<double[]>> sideZones = new HashMap<>();
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
        Set<Word> narrationAmounts = Collections.newSetFromMap(new IdentityHashMap<>());
        Map<Integer, Double> dateColX = dateColumns(rawRows);
        int narrationDated = 0;
        int mi = 0;
        while (mi < rawRows.size()) {
            RawRow r = rawRows.get(mi);
            if (Boolean.TRUE.equals(r.isNewTxn()) && !hasAmount(r.line())) {
                List<Line> block = new ArrayList<>();
                block.add(r.line());
                List<RawRow> markers = new ArrayList<>();
                int mj = mi + 1;
                while (mj < rawRows.size() && !Boolean.TRUE.equals(rawRows.get(mj).isNewTxn())) {
                    RawRow rj = rawRows.get(mj);
                    if (Boolean.FALSE.equals(rj.isNewTxn())) {
                        block.add(rj.line());
                    } else {
                        markers.add(rj); // keep marker rows (page-boundary B/F)
                    }
                    mj++;
                }
                merged.addAll(markers);
                if (isNarrationDate(r, block, dateColX)) {
                    // A date printed inside the narration ("Value Dt 02/02/2025 Ref ...") with
                    // no amount anywhere in its block is wrapped text, not a row: keep its lines
                    // as continuations (it still counts as a dropped dated line).
                    narrationDated++;
                    for (Line bl : block) {
                        merged.add(new RawRow(bl, false, null, r.cardLast4()));
                    }
                } else {
                    merged.addAll(splitBlock(r, block, narrationAmounts));
                }
                mi = mj;
            } else {
                merged.add(r);
                mi++;
            }
        }

        List<TxnDraft> txns = new ArrayList<>();
        int droppedRows = narrationDated;
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
                droppedRows++;
                continue;
            }
            LocalDate date = injectYear(parsedDate, periodStart, periodEnd);

            List<Word> words = ln.words();
            // pre-date columns (serial no etc.); chart labels like "62%" and print
            // counters like "22091/50000" in the margin overlap rows
            List<String> desc = new ArrayList<>();
            for (int k = 0; k < Math.min(start, words.size()); k++) {
                String t = words.get(k).text();
                if (!t.endsWith("%") && !PRINT_COUNTER.matcher(t).matches()) {
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
                if (inSideColumn(sideZones.computeIfAbsent(ln.page(), p -> sideColumns(pageHeaders.floorEntry(p))), w)) {
                    ci++;
                    continue;
                }
                String tok = Tokens.norm(w.text());
                String nxt = ci + 1 < words.size() ? Tokens.norm(words.get(ci + 1).text()) : "";
                Amount amt = Tokens.parseAmount(tok);
                if (amt != null && narrationAmounts.contains(w)) {
                    // figure printed inside the narration, not an amount column
                } else if (amt != null) {
                    AmountCell cell = new AmountCell(amt.value(), amt.sign() != 0 ? amt.sign() : pendingSign, w.x1(), tok);
                    cell.x0 = w.x0();
                    amounts.add(cell);
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
                    } else if (inTextRun(words, ci)) { // "AMAZON.IN - GROCERY"
                        desc.add(w.text());
                        descX.add(w.x0());
                        descX.add(w.x1());
                    }
                } else if (CR_DR_TOKENS.contains(tok)) {
                    if (!amounts.isEmpty()) { // standalone flag beside the amount cell
                        amounts.get(amounts.size() - 1).sign = tok.toLowerCase(Locale.ROOT).startsWith("c") ? 1 : -1;
                    }
                } else if (tok.length() == 1 && Character.isLetter(tok.charAt(0)) && !inTextRun(words, ci)) {
                    // indicator-column glyphs (dots/bullets extract as a letter) stand
                    // apart; an initial inside the narration ("K RATHINAVELU") is text
                } else {
                    desc.add(w.text());
                    descX.add(w.x0());
                    descX.add(w.x1());
                }
                ci++;
            }
            txns.add(new TxnDraft(date, desc, amounts, pos, ln.page(), ln.top(), descX, rr.cardLast4()));
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
        // narrations can sit above OR below their dated row — unless the document prints
        // them below its rows (narrationBelow): then the row above takes them. Lines
        // outside the description column (marketing text, section titles) are dropped.
        boolean below = narrationBelow(txns, continuations, pageSpan);
        List<Attachment> attachments = new ArrayList<>();
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
            TxnDraft near = below ? rowAbove(txns, pos, page) : null;
            if (near == null) {
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
            }
            List<String> words = new ArrayList<>();
            List<Word> lineWords = ln.words();
            for (int k = 0; k < lineWords.size(); k++) {
                String norm = Tokens.norm(lineWords.get(k).text());
                if (Tokens.parseAmount(norm) != null
                        || Tokens.isCurrencyToken(norm)
                        || (norm.length() == 1 && Character.isLetter(norm.charAt(0)) && !inTextRun(lineWords, k))) {
                    continue;
                }
                words.add(lineWords.get(k).text());
            }
            attachments.add(new Attachment(near, pos, page, top, words));
        }
        // A line joins its row only while each line sits within MAX_CONTINUATION_GAP of the
        // previous one attached (the row itself for the first): a real block gap such as a
        // footer under the last row stops the chain, a long wrapped narration does not.
        Map<TxnDraft, List<Attachment>> byRow = new IdentityHashMap<>();
        for (Attachment at : attachments) {
            if (at.row.page == at.page) {
                byRow.computeIfAbsent(at.row, k -> new ArrayList<>()).add(at);
            }
        }
        Set<Attachment> rejected = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Map.Entry<TxnDraft, List<Attachment>> e : byRow.entrySet()) {
            TxnDraft row = e.getKey();
            for (boolean above : new boolean[]{true, false}) {
                List<Attachment> side = new ArrayList<>();
                for (Attachment at : e.getValue()) {
                    if ((row.pos > at.pos) == above) {
                        side.add(at);
                    }
                }
                side.sort(Comparator.comparingDouble(at -> Math.abs(at.top - row.top)));
                double prev = row.top;
                boolean open = true;
                for (Attachment at : side) {
                    if (open && Math.abs(at.top - prev) > MAX_CONTINUATION_GAP) {
                        open = false;
                    }
                    if (open) {
                        prev = at.top;
                    } else {
                        rejected.add(at);
                    }
                }
            }
        }
        for (Attachment at : attachments) {
            if (rejected.contains(at)) {
                continue;
            }
            if (at.row.pos > at.pos) {
                at.row.desc.addAll(0, at.words);
            } else {
                at.row.desc.addAll(at.words);
            }
        }
        // A row with no amounts anywhere is not a transaction (stray dated
        // summary/footer lines that slipped past the filters).
        int before = txns.size();
        txns.removeIf(t -> t.amounts.isEmpty());
        droppedRows += before - txns.size();
        return new TxnBuild(txns, openingFromMarker, droppedRows);
    }

    // Letter-spaced figures extract as "5" + ",000.00" (kerning gap after the first digit):
    // rejoin them once, up front, when the pieces touch and form an amount, so every later
    // step (block splitting, narration figures, amount cells) sees the same words. The
    // date anchor's words are never touched, so its word indices stay valid.
    private static List<RawRow> rejoinSplitAmounts(List<RawRow> rawRows) {
        List<RawRow> out = new ArrayList<>(rawRows.size());
        for (RawRow r : rawRows) {
            List<Word> words = r.line().words();
            int from = r.anchor() != null ? r.anchor().start() + r.anchor().span() : 0;
            List<Word> joined = null;
            for (int i = 0; i < words.size(); i++) {
                Word w = words.get(i);
                if (i >= from && i + 1 < words.size()) {
                    Word nx = words.get(i + 1);
                    String tok = Tokens.norm(w.text());
                    String nxt = Tokens.norm(nx.text());
                    if (SPLIT_HEAD.matcher(tok).matches() && SPLIT_TAIL.matcher(nxt).matches()
                            && nx.x0() - w.x1() <= TEXT_GAP && Tokens.parseAmount(tok + nxt) != null) {
                        if (joined == null) {
                            joined = new ArrayList<>(words.subList(0, i));
                        }
                        joined.add(new Word(tok + nxt, w.x0(), nx.x1(), w.top(), w.page()));
                        i++;
                        continue;
                    }
                }
                if (joined != null) {
                    joined.add(w);
                }
            }
            out.add(joined == null ? r
                    : new RawRow(new Line(joined), r.isNewTxn(), r.anchor(), r.cardLast4()));
        }
        return out;
    }

    // Left edge of each page's date column: the leftmost row date on that page.
    private static Map<Integer, Double> dateColumns(List<RawRow> rows) {
        Map<Integer, Double> out = new HashMap<>();
        for (RawRow r : rows) {
            if (Boolean.TRUE.equals(r.isNewTxn()) && r.anchor() != null) {
                Line ln = r.line();
                out.merge(ln.page(), ln.words().get(r.anchor().start()).x0(), Math::min);
            }
        }
        return out;
    }

    // A dated line whose date sits right of its page's date column and whose block
    // carries no amount at all.
    private static boolean isNarrationDate(RawRow r, List<Line> block, Map<Integer, Double> dateColX) {
        if (r.anchor() == null) {
            return false;
        }
        Line ln = r.line();
        Double col = dateColX.get(ln.page());
        if (col == null || ln.words().get(r.anchor().start()).x0() - col <= DATE_COL_TOL) {
            return false;
        }
        for (Line bl : block) {
            if (hasAmount(bl)) {
                return false;
            }
        }
        return true;
    }

    // Whether this document prints each row's wrapped narration below its dated row (HDFC
    // savings: "... Value Dt 11/02/2025 Ref 504284727859" under the row), read from the gaps
    // between consecutive rows on a page: the widest vertical gap in the run row, lines...,
    // next row separates the two blocks. When that split clearly and consistently falls right
    // before the next row, every block starts at its dated row. Documents that print narration
    // above the row, or on both sides, keep the nearest-row rule.
    static boolean narrationBelow(List<TxnDraft> txns, List<Map.Entry<Integer, Line>> continuations,
                                  Map<Integer, double[]> pageSpan) {
        int below = 0;
        int above = 0;
        int votes = 0;
        for (int i = 0; i + 1 < txns.size(); i++) {
            TxnDraft r1 = txns.get(i);
            TxnDraft r2 = txns.get(i + 1);
            if (r1.page != r2.page || r2.top <= r1.top) {
                continue;
            }
            List<Double> tops = new ArrayList<>();
            for (Map.Entry<Integer, Line> e : continuations) {
                Line ln = e.getValue();
                if (e.getKey() > r1.pos && e.getKey() < r2.pos && ln.page() == r1.page
                        && inSpan(pageSpan.get(ln.page()), ln) && ln.top() > r1.top && ln.top() < r2.top) {
                    tops.add(ln.top());
                }
            }
            if (tops.isEmpty()) {
                continue;
            }
            Collections.sort(tops);
            tops.add(0, r1.top);
            tops.add(r2.top);
            int widest = -1;
            double best = Double.NEGATIVE_INFINITY;
            double second = Double.NEGATIVE_INFINITY;
            for (int g = 0; g + 1 < tops.size(); g++) {
                double gap = tops.get(g + 1) - tops.get(g);
                if (gap > best) {
                    second = best;
                    best = gap;
                    widest = g;
                } else if (gap > second) {
                    second = gap;
                }
            }
            if (best - second < BLOCK_GAP) {
                continue;
            }
            votes++;
            if (widest == tops.size() - 2) {
                below++;
            } else if (widest == 0) {
                above++;
            }
        }
        return below >= MIN_SIDE_VOTES && below >= SIDE_LEAD * above && below >= SIDE_SHARE * votes;
    }

    // In a narration-below document, the nearest row before the line on its page; null when
    // there is none (the nearest-row rule decides then).
    private static TxnDraft rowAbove(List<TxnDraft> txns, int pos, int page) {
        TxnDraft found = null;
        for (TxnDraft t : txns) {
            if (t.page == page && t.pos < pos && (found == null || t.pos > found.pos)) {
                found = t;
            }
        }
        return found;
    }

    private static boolean inSpan(double[] span, Line ln) {
        double x0 = ln.words().get(0).x0();
        return span == null || (span[0] - 25 <= x0 && x0 <= span[1] + 25);
    }

    private static boolean hasAmount(Line line) {
        return line.words().stream().anyMatch(w -> Tokens.parseAmount(Tokens.norm(w.text())) != null);
    }

    // A dated block whose lines carry no amounts until a later line. A "closing"
    // line holds an amount plus the balance at the block's right edge; it ends one
    // transaction. HSBC prints the date once per day, so a block can hold several
    // closing lines — one per same-day transaction — and each becomes its own row
    // under the block's date. Figures left of every amount column (the "INR
    // 10000.00" in a card-swipe narration) are recorded as narration, not amounts.
    private static List<RawRow> splitBlock(RawRow dated, List<Line> block, Set<Word> narrationAmounts) {
        double right = Double.NEGATIVE_INFINITY;
        double rightX0 = 0;
        for (Line ln : block) {
            for (Word w : ln.words()) {
                if (Tokens.parseAmount(Tokens.norm(w.text())) != null && w.x1() > right) {
                    right = w.x1();
                    rightX0 = w.x0();
                }
            }
        }
        List<Integer> closers = new ArrayList<>();
        double amountLeft = Double.POSITIVE_INFINITY;
        for (int i = 0; i < block.size(); i++) {
            List<Word> amts = amountWords(block.get(i));
            // a balance column is right-aligned, or (Jupiter) left-aligned so a short "₹0" ends well left of the edge
            Word lastAmt = amts.isEmpty() ? null : amts.get(amts.size() - 1);
            if (amts.size() >= 2 && (Math.abs(lastAmt.x1() - right) <= Tokens.COL_TOL
                    || Math.abs(lastAmt.x0() - rightX0) <= Tokens.COL_TOL)) {
                closers.add(i);
                for (Word w : amts) {
                    amountLeft = Math.min(amountLeft, w.x0());
                }
            }
        }
        if (closers.isEmpty()) {
            return List.of(new RawRow(new Line(flatten(block, 0, block.size())), true, dated.anchor(), dated.cardLast4()));
        }
        Set<Integer> closerSet = new HashSet<>(closers);
        for (int i = 0; i < block.size(); i++) {
            if (closerSet.contains(i)) {
                continue;
            }
            for (Word w : amountWords(block.get(i))) {
                if (w.x1() < amountLeft - Tokens.COL_TOL) {
                    narrationAmounts.add(w);
                }
            }
        }

        DateAnchor anchor = dated.anchor();
        List<Word> dateWords = dated.line().words().subList(anchor.start(), anchor.start() + anchor.span());
        List<RawRow> rows = new ArrayList<>();
        int from = 0;
        for (int c = 0; c < closers.size(); c++) {
            int to = c == closers.size() - 1 ? block.size() : closers.get(c) + 1;
            List<Word> words = flatten(block, from, to);
            if (c == 0) {
                rows.add(new RawRow(new Line(words), true, anchor, dated.cardLast4()));
            } else {
                // Undated sibling: inherit the block's date, positioned at its own first line.
                Word first = words.get(0);
                List<Word> withDate = new ArrayList<>();
                for (Word d : dateWords) {
                    withDate.add(new Word(d.text(), d.x0(), d.x1(), first.top(), first.page()));
                }
                withDate.addAll(words);
                rows.add(new RawRow(new Line(withDate), true,
                        new DateAnchor(0, anchor.span(), anchor.text()), dated.cardLast4()));
            }
            from = to;
        }
        return rows;
    }

    private static List<Word> amountWords(Line line) {
        List<Word> out = new ArrayList<>();
        for (Word w : line.words()) {
            if (Tokens.parseAmount(Tokens.norm(w.text())) != null) {
                out.add(w);
            }
        }
        return out;
    }

    private static List<Word> flatten(List<Line> block, int from, int to) {
        List<Word> words = new ArrayList<>();
        for (int i = from; i < to; i++) {
            words.addAll(block.get(i).words());
        }
        return words;
    }

    // A word printed right beside another narration word belongs to that text.
    private static boolean inTextRun(List<Word> words, int i) {
        Word w = words.get(i);
        return (i > 0 && isNarrationWord(words.get(i - 1)) && w.x0() - words.get(i - 1).x1() <= TEXT_GAP)
                || (i + 1 < words.size() && isNarrationWord(words.get(i + 1))
                        && words.get(i + 1).x0() - w.x1() <= TEXT_GAP);
    }

    private static boolean isNarrationWord(Word w) {
        String t = Tokens.norm(w.text());
        return Tokens.parseAmount(t) == null && !Tokens.isCurrencyToken(t);
    }

    // Serial-number and reward-point columns (ICICI "SerNo.", "Reward") hold one
    // short value under the header word; it is not part of the narration.
    private static List<double[]> sideColumns(Map.Entry<Integer, Line> header) {
        List<double[]> zones = new ArrayList<>();
        if (header == null) {
            return zones;
        }
        for (Word w : header.getValue().words()) {
            String wl = w.text().toLowerCase(Locale.ROOT);
            if (wl.startsWith("serno") || wl.startsWith("serial") || wl.startsWith("reward")) {
                zones.add(new double[]{w.x0(), w.x1()});
            }
        }
        // Axis "CASHBACK EARNED": its cells ("4.00 Cr", "0.00 Dr") print left of the heading
        // and look exactly like amounts; they are never the amount. Only a heading with both
        // words side by side declares the zone, so a lone "Cashback" word elsewhere does not.
        List<Word> hw = header.getValue().words();
        for (int i = 0; i + 1 < hw.size(); i++) {
            if (hw.get(i).text().equalsIgnoreCase("cashback") && hw.get(i + 1).text().equalsIgnoreCase("earned")) {
                zones.add(new double[]{hw.get(i).x0() - CASHBACK_LEFT_PAD, hw.get(i + 1).x1()});
            }
        }
        return zones;
    }

    private static boolean inSideColumn(List<double[]> zones, Word w) {
        double center = (w.x0() + w.x1()) / 2;
        for (double[] z : zones) {
            if (z[0] <= center && center <= z[1]) {
                return true;
            }
        }
        return false;
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
