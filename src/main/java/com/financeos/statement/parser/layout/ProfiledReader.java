package com.financeos.statement.parser.layout;

import com.financeos.statement.parser.Line;
import com.financeos.statement.parser.ReadOutcome;
import com.financeos.statement.parser.RowResult;
import com.financeos.statement.parser.StatementMeta;
import com.financeos.statement.parser.StatementParseEngine;
import com.financeos.statement.parser.Word;
import com.financeos.statement.parser.layout.LayoutProfile.Column;
import com.financeos.statement.parser.layout.LayoutProfile.Direction;
import com.financeos.statement.parser.layout.LayoutProfile.Edge;
import com.financeos.statement.parser.layout.LayoutProfile.EndMarker;
import com.financeos.statement.parser.layout.LayoutProfile.EndScope;
import com.financeos.statement.parser.layout.LayoutProfile.HeaderLabel;
import com.financeos.statement.parser.layout.LayoutProfile.Position;
import com.financeos.statement.parser.layout.LayoutProfile.Role;
import com.financeos.statement.parser.layout.LayoutProfile.Sign;
import com.financeos.statement.parser.layout.LayoutProfile.Summary;
import com.financeos.statement.parser.layout.LayoutProfile.TableRow;
import com.financeos.statement.parser.layout.LayoutProfile.ValueType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads statement lines with one {@link LayoutProfile}: finds the table header on each page, assigns the words of every
 * dated line to columns by x-range relative to the header labels, signs the amounts by the profile's rule and harvests
 * the declared summary values. Returns the same intermediate as the v1 reader (meta, signed rows, mode, summary fields)
 * so the engine's shared finish step takes over unchanged. Pure: no Spring, no I/O.
 */
public final class ProfiledReader {

    /** outcome: what finish takes; unreadAmountLines: lines in the table region holding an amount that were read as nothing. */
    public record Result(ReadOutcome outcome, int unreadAmountLines) {
    }

    private ProfiledReader() {
    }

    private enum Kind { OUTSIDE, STRAY, HEADER, FURNITURE, SECTION, BODY }

    /** The header of one page: where it is and the x0 of each named label on it. */
    private record Table(int page, int firstIdx, int headerIdx, Map<String, Double> labelX0, boolean continuation,
                         Double firstDateX) {
    }

    private record Spans(String text, List<int[]> ranges) {
        Word wordAt(List<Word> words, int offset) {
            for (int i = 0; i < ranges.size(); i++) {
                if (offset >= ranges.get(i)[0] && offset < ranges.get(i)[1]) {
                    return words.get(i);
                }
            }
            return words.get(words.size() - 1);
        }
    }

    private static Spans spans(Line ln) {
        StringBuilder sb = new StringBuilder();
        List<int[]> ranges = new ArrayList<>();
        for (Word w : ln.words()) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            ranges.add(new int[]{sb.length(), sb.length() + w.text().length()});
            sb.append(w.text());
        }
        return new Spans(sb.toString(), ranges);
    }

    // ---- header detection -------------------------------------------------------------------------------------

    /** A matched header: the index of its last line and the x0 of each label on the line it sits. */
    private record HeaderHit(int lastIdx, Map<String, Double> labelX0) {
    }

    /** The x0 of each header label when the line alone is the profile's table header, else null. */
    static Map<String, Double> tryHeader(Line ln, LayoutProfile p) {
        HeaderHit h = headerAt(List.of(ln), 0, p);
        return h == null ? null : h.labelX0();
    }

    /**
     * The header starting at line i: labels matched in order over a window of up to headerLines consecutive lines of one
     * page (the smallest window that fits), the first label on the first line. Null when there is none.
     */
    private static HeaderHit headerAt(List<Line> lines, int i, LayoutProfile p) {
        for (int k = 1; k <= p.headerLines() && i + k <= lines.size(); k++) {
            if (k > 1 && lines.get(i + k - 1).page() != lines.get(i).page()) {
                break;
            }
            HeaderHit h = matchWindow(lines, i, k, p);
            if (h != null) {
                return h;
            }
        }
        return null;
    }

    private static HeaderHit matchWindow(List<Line> lines, int first, int k, LayoutProfile p) {
        List<Spans> sps = new ArrayList<>();
        for (int j = 0; j < k; j++) {
            Spans sp = spans(lines.get(first + j));
            for (Pattern reject : p.headerRejects()) {
                if (reject.matcher(sp.text()).find()) {
                    return null;
                }
            }
            sps.add(sp);
        }
        Map<String, Double> x0 = new LinkedHashMap<>();
        int line = 0;
        int pos = 0;
        for (HeaderLabel h : p.header()) {
            Matcher m = null;
            while (line < k) {
                m = h.regex().matcher(sps.get(line).text());
                if (m.find(pos)) {
                    break;
                }
                m = null;
                if (x0.isEmpty()) {
                    return null; // the first label must be on the first line
                }
                line++;
                pos = 0;
            }
            if (m == null) {
                return null;
            }
            x0.put(h.name(), sps.get(line).wordAt(lines.get(first + line).words(), m.start()).x0());
            pos = Math.max(m.end(), pos + 1);
        }
        if (line != k - 1) {
            return null; // the last label sits on the last line
        }
        for (LayoutProfile.LabelAt g : p.labelGeometry()) {
            Double x = x0.get(g.label());
            if (x == null || x < g.minX() || x > g.maxX()) {
                return null;
            }
        }
        return new HeaderHit(first + k - 1, x0);
    }

    private static Map<Integer, Table> findHeaders(List<Line> lines, LayoutProfile p) {
        Map<Integer, Table> tables = new LinkedHashMap<>();
        for (int i = 0; i < lines.size(); i++) {
            Line ln = lines.get(i);
            if (tables.containsKey(ln.page())) {
                continue;
            }
            HeaderHit h = headerAt(lines, i, p);
            if (h != null) {
                tables.put(ln.page(), new Table(ln.page(), i, h.lastIdx(), h.labelX0(), false, null));
            }
        }
        return tables;
    }

    /** True when the profile's header is found on some page. */
    static boolean hasHeader(List<Line> lines, LayoutProfile p) {
        for (int i = 0; i < lines.size(); i++) {
            if (headerAt(lines, i, p) != null) {
                return true;
            }
        }
        return false;
    }

    // ---- reading ----------------------------------------------------------------------------------------------

    private static final class State {
        final LayoutProfile p;
        final List<Line> lines;
        final Kind[] kind;
        final Table[] table;
        final String[] last4;
        final DateResolver dates;
        LocalDate periodStart;
        LocalDate periodEnd;
        int unread;
        boolean[] scannable;
        int summaryMaxPage;
        int dropped;

        State(LayoutProfile p, List<Line> lines) {
            this.p = p;
            this.lines = lines;
            this.kind = new Kind[lines.size()];
            this.table = new Table[lines.size()];
            this.last4 = new String[lines.size()];
            this.dates = new DateResolver(p.dateFormats());
        }
    }

    public static Result read(List<Line> lines, LayoutProfile p) {
        State st = new State(p, lines);
        classify(st);
        StatementMeta meta = new StatementMeta();
        meta.bank = p.bank();
        LinkedHashMap<String, Object> summaryFields = new LinkedHashMap<>();
        int maxBody = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (st.kind[i] == Kind.BODY) {
                maxBody = Math.max(maxBody, lines.get(i).page());
            }
        }
        // summary values are read from the table pages and the trailing non-prose summary pages only, never terms text
        st.scannable = new boolean[lines.size()];
        Map<Integer, List<Resolved>> geometry = new HashMap<>();
        for (int i = 0; i < lines.size(); i++) {
            if (st.kind[i] == Kind.OUTSIDE || st.kind[i] == Kind.STRAY) {
                st.scannable[i] = true;
            } else if (st.kind[i] == Kind.BODY) {
                // a table line is searched only when it is not a dated row (a summary printed on a continued page)
                Table tbl = st.table[i];
                List<Resolved> cols = geometry.computeIfAbsent(tbl.page(), k -> resolve(p, tbl));
                st.scannable[i] = dateOf(st, lines.get(i), cols) == null;
            }
        }
        st.summaryMaxPage = maxBody < 0 ? -1 : StatementParseEngine.harvestWindow(lines, maxBody);
        harvestSummary(st, meta, summaryFields);

        List<Raw> raws = p.rowShape() == LayoutProfile.RowShape.CUSTOM ? customRows(st) : singleLineRows(st);
        if (raws.isEmpty()) {
            throw new IllegalStateException("layout " + p.id() + " matched but read no rows");
        }
        applyHeaderRegionLast4(st, raws, meta);
        orderRows(raws, p);
        countStray(st);

        boolean isCreditCard = p.product() == LayoutProfile.Product.CARD;
        BigDecimal opening = meta.openingBalance == null ? null : BigDecimal.valueOf(meta.openingBalance);
        List<RowResult> results = new ArrayList<>();
        int validRows = 0;
        BigDecimal prev = opening;
        BigDecimal openingUsed = null;
        boolean first = true;
        for (Raw r : raws) {
            int sign = r.sign;
            String source = r.source;
            if (p.sign() == Sign.BALANCE_DELTA) {
                if (prev != null && r.balance != null) {
                    sign = r.balance.compareTo(prev) >= 0 ? 1 : -1;
                    source = "balance";
                } else {
                    // no opening balance: the direction of this row cannot be derived and is not guessed
                    st.unread++;
                    prev = r.balance;
                    first = false;
                    continue;
                }
            }
            BigDecimal signed = sign >= 0 ? r.magnitude : r.magnitude.negate();
            boolean valid = false;
            if (r.balance != null) {
                if (prev != null) {
                    valid = prev.add(signed).subtract(r.balance).abs().doubleValue() < StatementParseEngine.CHAIN_TOLERANCE;
                } else if (first) {
                    openingUsed = r.balance.subtract(signed);
                    valid = true;
                }
            }
            RowResult rr = new RowResult(r.date, r.description, signed.doubleValue(),
                    r.balance == null ? null : r.balance.doubleValue(), valid);
            rr.signSource = source;
            rr.cardLast4 = r.cardLast4;
            results.add(rr);
            if (valid) {
                validRows++;
            }
            prev = r.balance;
            first = false;
        }

        if (results.isEmpty()) {
            throw new IllegalStateException("layout " + p.id() + " read no signed rows");
        }
        String mode;
        if (p.hasBalanceColumn() && !results.isEmpty() && (double) validRows / results.size() >= 0.6) {
            mode = "balance-chain";
            if (meta.openingBalance == null && openingUsed != null) {
                meta.openingBalance = openingUsed.doubleValue();
            }
        } else {
            for (RowResult r : results) {
                r.balance = null;
                r.chainValid = false;
            }
            mode = "heuristic (no balance column validated)";
            List<RowResult> reconciled =
                    StatementParseEngine.reconcileOpeningClosing(results, meta, summaryFields, isCreditCard);
            if (reconciled != null) {
                results = reconciled;
                mode = "opening/closing reconciliation";
            }
        }

        Set<Line> txnLines = Collections.newSetFromMap(new IdentityHashMap<>());
        int maxTxnPage = 0;
        for (Raw r : raws) {
            txnLines.add(r.line);
            maxTxnPage = Math.max(maxTxnPage, r.line.page());
        }
        int harvestMaxPage = StatementParseEngine.harvestWindow(lines, maxTxnPage);
        ReadOutcome outcome = new ReadOutcome(meta, results, mode, st.dropped + st.unread, txnLines, maxTxnPage,
                harvestMaxPage, summaryFields, new LinkedHashSet<>(), isCreditCard);
        return new Result(outcome, st.unread);
    }

    // ---- table region -----------------------------------------------------------------------------------------

    /**
     * Marks every line: outside the table (before it started or after it ended for good), stray (inside the table's
     * span but in no table region), header, furniture (end marker / skip), card section header, or body. A page without
     * the header continues the table with the previous page's geometry unless the profile says otherwise.
     */
    private static void classify(State st) {
        LayoutProfile p = st.p;
        Map<Integer, Table> tables = findHeaders(st.lines, p);
        boolean ended = false;
        boolean started = false;
        Table cur = null;
        Table last = null;
        int lastPage = Integer.MIN_VALUE;
        String last4 = null;
        for (int i = 0; i < st.lines.size(); i++) {
            Line ln = st.lines.get(i);
            st.kind[i] = Kind.OUTSIDE;
            if (ended) {
                continue;
            }
            if (ln.page() != lastPage) {
                lastPage = ln.page();
                if (started && p.continuesWithoutHeader() && !tables.containsKey(ln.page())
                        && StatementParseEngine.isProse(st.lines, ln.page())) {
                    ended = true; // a terms page never continues the table
                    continue;
                }
                // a page that repeats the header waits for it; one that does not continues the table
                cur = null;
                if (!tables.containsKey(ln.page()) && started && p.continuesWithoutHeader()) {
                    cur = p.continuationColumns().isEmpty() ? last
                            : new Table(ln.page(), -1, -1, last.labelX0(), true, firstDateX(st, ln.page()));
                }
            }
            Table t = tables.get(ln.page());
            if (t != null && i >= t.firstIdx() && i < t.headerIdx()) {
                st.kind[i] = Kind.HEADER; // an earlier line of a header that spans several
                continue;
            }
            if (t != null && t.headerIdx() == i) {
                cur = t;
                last = t;
                started = true;
                st.kind[i] = Kind.HEADER;
                continue;
            }
            if (cur == null) {
                if (started) {
                    st.kind[i] = Kind.STRAY;
                    st.table[i] = last;
                }
                continue;
            }
            st.table[i] = cur;
            String text = ln.text();
            EndMarker end = null;
            for (EndMarker m : p.endMarkers()) {
                if (m.regex().matcher(text).find()) {
                    end = m;
                    break;
                }
            }
            if (end != null) {
                st.kind[i] = Kind.FURNITURE;
                if (end.scope() == EndScope.FOR_GOOD) {
                    ended = true;
                }
                cur = null;
                continue;
            }
            if (matchesAny(p.skipLines(), text)) {
                st.kind[i] = Kind.FURNITURE;
                continue;
            }
            if (p.sectionHeader() != null) {
                Matcher m = p.sectionHeader().matcher(text);
                if (m.find()) {
                    last4 = m.groupCount() >= 1 ? m.group(1) : null;
                    st.kind[i] = Kind.SECTION;
                    continue;
                }
            }
            st.kind[i] = Kind.BODY;
            st.last4[i] = last4;
        }
    }

    /** Lines inside the table's span that no region consumed, holding an amount or a date in the date zone. */
    private static void countStray(State st) {
        Map<Integer, List<Resolved>> colsByPage = new HashMap<>();
        for (int i = 0; i < st.lines.size(); i++) {
            if (st.kind[i] != Kind.STRAY) {
                continue;
            }
            Line ln = st.lines.get(i);
            Table tbl = st.table[i];
            List<Resolved> cols = colsByPage.computeIfAbsent(tbl.page(), k -> resolve(st.p, tbl));
            if (hasAmountToken(ln, cols) || dateOf(st, ln, cols) != null) {
                st.unread++;
            }
        }
    }

    private static boolean matchesAny(List<Pattern> patterns, String text) {
        for (Pattern pt : patterns) {
            if (pt.matcher(text).find()) {
                return true;
            }
        }
        return false;
    }

    // ---- rows -------------------------------------------------------------------------------------------------

    private static final class Raw {
        Line line;
        LocalDate date;
        String description;
        BigDecimal magnitude;
        int sign;
        String source;
        BigDecimal balance;
        String cardLast4;
        final List<String> above = new ArrayList<>();
        final List<String> below = new ArrayList<>();
    }

    private record Resolved(Role role, double lo, double hi) {
    }

    /** x0 of the leftmost date token of the page (a word that parses as a full date), or null. */
    private static Double firstDateX(State st, int page) {
        Double best = null;
        for (Line ln : st.lines) {
            if (ln.page() != page) {
                continue;
            }
            for (Word w : ln.words()) {
                if (st.dates.parse(Amounts.norm(w.text()), null, null) != null && (best == null || w.x0() < best)) {
                    best = w.x0();
                }
            }
        }
        return best;
    }

    private static List<Resolved> resolve(LayoutProfile p, Table t) {
        List<Resolved> out = new ArrayList<>();
        boolean cont = t.continuation() && !p.continuationColumns().isEmpty();
        if (cont && t.firstDateX() == null && p.continuationColumns().stream().anyMatch(
                c -> LayoutProfile.FIRST_DATE.equals(c.from().label()) || LayoutProfile.FIRST_DATE.equals(c.to().label()))) {
            cont = false; // no date on the page to anchor to: the previous page's geometry
        }
        if (cont) {
            for (Column c : p.continuationColumns()) {
                out.add(new Resolved(c.role(), contEdge(c.from(), t), contEdge(c.to(), t)));
            }
            return out;
        }
        for (Column c : p.columns()) {
            out.add(new Resolved(c.role(), edge(c.from(), t), edge(c.to(), t)));
        }
        return out;
    }

    private static double contEdge(Edge e, Table t) {
        return e.label() == null ? e.dx() : t.firstDateX() + e.dx();
    }

    private static double edge(Edge e, Table t) {
        return e.label() == null ? e.dx() : t.labelX0().get(e.label()) + e.dx();
    }

    private static Role roleOf(List<Resolved> cols, Word w) {
        for (Resolved c : cols) {
            if (w.x0() >= c.lo() && w.x0() < c.hi()) {
                return c.role();
            }
        }
        return null;
    }

    private record Pend(Line line, Table table, int rowBefore) {
    }

    private static List<Raw> singleLineRows(State st) {
        LayoutProfile p = st.p;
        List<Raw> rows = new ArrayList<>();
        List<Pend> pending = new ArrayList<>();
        Map<Integer, List<Resolved>> colsByPage = new HashMap<>();
        for (int i = 0; i < st.lines.size(); i++) {
            if (st.kind[i] != Kind.BODY) {
                continue;
            }
            Line ln = st.lines.get(i);
            Table tbl = st.table[i];
            List<Resolved> cols = colsByPage.computeIfAbsent(tbl.page(), k -> resolve(p, tbl));
            Raw r = readRow(st, ln, cols, st.last4[i]);
            if (r != null) {
                rows.add(r);
                continue;
            }
            if (isRowLine(st, ln, cols)) {
                continue; // a dated line read as nothing was already counted as dropped
            }
            boolean continuation = isContinuationCandidate(ln, cols);
            if (continuation) {
                pending.add(new Pend(ln, st.table[i], rows.size() - 1));
            } else if (hasAmountToken(ln, cols)) {
                st.unread++;
            }
        }
        attachContinuations(st, rows, pending);
        return rows;
    }

    /** An amount token outside any IGNORE column (IGNORE swallows every token of its range: dates, times, amounts, signs). */
    private static boolean hasAmountToken(Line ln, List<Resolved> cols) {
        for (Word w : ln.words()) {
            if (roleOf(cols, w) != Role.IGNORE && Amounts.isAmount(w.text())) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasAmountToken(Line ln) {
        for (Word w : ln.words()) {
            if (Amounts.isAmount(w.text())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isContinuationCandidate(Line ln, List<Resolved> cols) {
        boolean narration = false;
        for (Word w : ln.words()) {
            Role r = roleOf(cols, w);
            if (r == Role.NARRATION) {
                narration = true;
            } else if (r == Role.DATE || r == Role.DEBIT || r == Role.CREDIT || r == Role.AMOUNT
                    || r == Role.BALANCE || r == Role.MARKER) {
                return false;
            }
        }
        return narration;
    }

    /** Dated line the row reader rejected (no usable amount): it was counted as dropped, not unread. */
    private static boolean isRowLine(State st, Line ln, List<Resolved> cols) {
        return dateOf(st, ln, cols) != null;
    }

    private static LocalDate dateOf(State st, Line ln, List<Resolved> cols) {
        StringBuilder sb = new StringBuilder();
        for (Word w : ln.words()) {
            if (roleOf(cols, w) == Role.DATE) {
                if (sb.length() > 0) {
                    sb.append(' ');
                }
                sb.append(Amounts.norm(w.text()));
            }
        }
        if (sb.length() == 0) {
            return null;
        }
        return st.dates.parse(sb.toString(), st.periodStart, st.periodEnd);
    }

    /** A row from one dated line, or null when the line has no date (or, with a date, no usable amount: dropped). */
    private static Raw readRow(State st, Line ln, List<Resolved> cols, String last4) {
        LayoutProfile p = st.p;
        LocalDate date = dateOf(st, ln, cols);
        if (date == null) {
            return null;
        }
        List<String> narration = new ArrayList<>();
        Map<Role, List<Word>> amountWords = new EnumMap<>(Role.class);
        boolean strayAmount = false;
        int amountIdx = -1;
        int amountCells = 0;
        List<Word> ws = ln.words();
        for (int i = 0; i < ws.size(); i++) {
            Word w = ws.get(i);
            Role role = roleOf(cols, w);
            if (role == null) {
                strayAmount |= Amounts.isAmount(w.text());
            } else if (role == Role.NARRATION) {
                narration.add(w.text());
            } else if (role == Role.DEBIT || role == Role.CREDIT || role == Role.AMOUNT || role == Role.BALANCE) {
                amountWords.computeIfAbsent(role, k -> new ArrayList<>()).add(w);
                if (role == Role.AMOUNT && Amounts.isAmount(w.text())) {
                    amountIdx = i;
                    amountCells++;
                }
            }
        }
        // SUFFIX: the marker is the credit/debit token right after the amount (or glued to it), wherever its x falls;
        // any other credit/debit-like word on the row means the sign cannot be trusted: the row is counted unread
        String adjacent = null;
        boolean strayMarker = false;
        if (p.sign() == Sign.SUFFIX) {
            int adjIdx = -1;
            if (amountCells == 1 && amountIdx + 1 < ws.size() && markerLike(p, Amounts.norm(ws.get(amountIdx + 1).text()))) {
                adjIdx = amountIdx + 1;
                adjacent = Amounts.norm(ws.get(adjIdx).text());
            }
            for (int i = 0; i < ws.size(); i++) {
                if (i != adjIdx && !exempt(roleOf(cols, ws.get(i)))
                        && markerLike(p, Amounts.norm(ws.get(i).text()))) {
                    strayMarker = true;
                }
            }
        }
        if (p.sign() == Sign.PREFIX) {
            // PREFIX: the marker is the token right before the amount (or before a currency glyph in front of it)
            int markIdx = -1;
            if (amountCells == 1) {
                int k = amountIdx - 1;
                if (k >= 0 && Amounts.isCurrencyToken(Amounts.norm(ws.get(k).text()))) {
                    k--;
                }
                if (k >= 0 && prefixLike(p, Amounts.norm(ws.get(k).text()))) {
                    markIdx = k;
                    adjacent = Amounts.norm(ws.get(k).text());
                }
            }
            for (int i = 0; i < ws.size(); i++) {
                if (i != markIdx && !exempt(roleOf(cols, ws.get(i)))
                        && prefixLike(p, Amounts.norm(ws.get(i).text()))) {
                    strayMarker = true;
                }
            }
        }
        if (strayAmount || strayMarker) {
            st.unread++;
        }
        Amounts.Parsed debit = single(amountWords.get(Role.DEBIT));
        Amounts.Parsed credit = single(amountWords.get(Role.CREDIT));
        Amounts.Parsed amount = single(amountWords.get(Role.AMOUNT));
        Amounts.Parsed balance = single(amountWords.get(Role.BALANCE));
        Raw r = new Raw();
        r.line = ln;
        r.date = date;
        r.description = String.join(" ", narration);
        r.cardLast4 = last4;
        if (balance != null) {
            r.balance = balance.value().multiply(balance.negativeMarked() ? BigDecimal.ONE.negate() : BigDecimal.ONE);
        }
        switch (p.sign()) {
            case SEPARATE_COLUMNS -> {
                if (debit != null && credit == null && !ambiguous(amountWords, Role.CREDIT)) {
                    r.magnitude = debit.value();
                    r.sign = -1;
                } else if (credit != null && debit == null && !ambiguous(amountWords, Role.DEBIT)) {
                    r.magnitude = credit.value();
                    r.sign = 1;
                } else {
                    st.dropped++;
                    return null;
                }
                r.source = "column";
            }
            case SUFFIX -> {
                if (amount == null) {
                    st.dropped++;
                    return null;
                }
                r.magnitude = amount.value();
                String token = amount.suffix() != null ? amount.suffix() : adjacent;
                if (token != null && token.equalsIgnoreCase(p.suffix().creditToken())) {
                    r.sign = 1;
                    r.source = "suffix";
                } else if (token != null && token.equalsIgnoreCase(p.suffix().debitToken())) {
                    r.sign = -1;
                    r.source = "suffix";
                } else {
                    r.sign = p.suffix().defaultDirection() == Direction.CREDIT ? 1 : -1;
                    r.source = "default";
                }
            }
            case PREFIX -> {
                if (amount == null) {
                    st.dropped++;
                    return null;
                }
                r.magnitude = amount.value();
                if (adjacent != null && p.prefix().credit().matcher(adjacent).matches()) {
                    r.sign = 1;
                    r.source = "prefix";
                } else if (adjacent != null && p.prefix().debit() != null && p.prefix().debit().matcher(adjacent).matches()) {
                    r.sign = -1;
                    r.source = "prefix";
                } else {
                    r.sign = p.prefix().defaultDirection() == Direction.CREDIT ? 1 : -1;
                    r.source = "default";
                }
            }
            case MINUS_IS_CREDIT -> {
                if (amount == null) {
                    st.dropped++;
                    return null;
                }
                r.magnitude = amount.value();
                r.sign = amount.negativeMarked() ? 1 : -1;
                r.source = "minus";
            }
            case BALANCE_DELTA -> {
                if (amount == null) {
                    st.dropped++;
                    return null;
                }
                r.magnitude = amount.value();
                r.source = "balance";
            }
            default -> throw new IllegalStateException();
        }
        return r;
    }

    /** Narration text and IGNORE cells (an amount with its own Cr/Dr, a cashback cell) never make a stray marker. */
    private static boolean exempt(Role role) {
        return role == Role.NARRATION || role == Role.IGNORE;
    }

    private static boolean prefixLike(LayoutProfile p, String token) {
        return p.prefix().credit().matcher(token).matches()
                || (p.prefix().debit() != null && p.prefix().debit().matcher(token).matches());
    }

    private static boolean markerLike(LayoutProfile p, String token) {
        return token.equalsIgnoreCase("cr") || token.equalsIgnoreCase("dr")
                || token.equalsIgnoreCase(p.suffix().creditToken()) || token.equalsIgnoreCase(p.suffix().debitToken());
    }

    private static Amounts.Parsed single(List<Word> words) {
        if (words == null) {
            return null;
        }
        Amounts.Parsed found = null;
        for (Word w : words) {
            Amounts.Parsed a = Amounts.parse(w.text());
            if (a != null) {
                if (found != null) {
                    return null; // two figures in one cell: not readable
                }
                found = a;
            }
        }
        return found;
    }

    private static boolean ambiguous(Map<Role, List<Word>> words, Role role) {
        List<Word> ws = words.get(role);
        if (ws == null) {
            return false;
        }
        for (Word w : ws) {
            if (Amounts.isAmount(w.text())) {
                return true;
            }
        }
        return false;
    }

    /** Narration-only lines join the nearest row: below it within continuationBelow, above it within continuationAbove. */
    private static void attachContinuations(State st, List<Raw> rows, List<Pend> pending) {
        for (int k = 0; k < pending.size(); k++) {
            Line ln = pending.get(k).line();
            int b = pending.get(k).rowBefore();
            Raw prev = b >= 0 ? rows.get(b) : null;
            if (prev != null && prev.line.page() != ln.page()) {
                prev = null;
            }
            Raw next = null;
            for (int j = b + 1; j < rows.size(); j++) {
                Raw cand = rows.get(j);
                if (cand.line.page() == ln.page() && cand.line.top() >= ln.top()) {
                    next = cand;
                    break;
                }
                if (cand.line.page() > ln.page()) {
                    break;
                }
            }
            double gapBelow = prev == null ? Double.MAX_VALUE : ln.top() - prev.line.top();
            double gapAbove = next == null ? Double.MAX_VALUE : next.line.top() - ln.top();
            boolean canBelow = prev != null && st.p.continuationBelow() > 0 && gapBelow <= st.p.continuationBelow();
            boolean canAbove = next != null && st.p.continuationAbove() > 0 && gapAbove <= st.p.continuationAbove();
            String text = narrationText(st, ln, pending.get(k).table());
            if (canBelow && (!canAbove || gapBelow <= gapAbove)) {
                prev.below.add(text);
            } else if (canAbove) {
                next.above.add(text);
            } else if (hasAmountToken(ln)) {
                st.unread++;
            }
        }
        for (Raw r : rows) {
            if (!r.above.isEmpty() || !r.below.isEmpty()) {
                List<String> all = new ArrayList<>(r.above);
                all.add(r.description);
                all.addAll(r.below);
                all.removeIf(String::isEmpty);
                r.description = String.join(" ", all);
            }
        }
    }

    private static String narrationText(State st, Line ln, Table table) {
        List<Resolved> cols = resolve(st.p, table);
        List<String> t = new ArrayList<>();
        for (Word w : ln.words()) {
            if (roleOf(cols, w) == Role.NARRATION) {
                t.add(w.text());
            }
        }
        return String.join(" ", t);
    }

    private static List<Raw> customRows(State st) {
        List<Raw> out = new ArrayList<>();
        Map<Integer, List<Line>> byPage = new LinkedHashMap<>();
        Map<Integer, Table> tableOf = new HashMap<>();
        for (int i = 0; i < st.lines.size(); i++) {
            if (st.kind[i] == Kind.BODY) {
                byPage.computeIfAbsent(st.lines.get(i).page(), k -> new ArrayList<>()).add(st.lines.get(i));
                tableOf.put(st.lines.get(i).page(), st.table[i]);
            }
        }
        LayoutProfile.DateContext dc = text -> st.dates.parse(text, st.periodStart, st.periodEnd);
        for (Map.Entry<Integer, List<Line>> e : byPage.entrySet()) {
            for (TableRow tr : st.p.rowStrategy().read(e.getValue(), tableOf.get(e.getKey()).labelX0(), dc)) {
                Raw r = new Raw();
                r.line = tr.line();
                r.date = tr.date();
                r.description = tr.description() == null ? "" : tr.description();
                r.balance = tr.balance();
                r.cardLast4 = tr.cardLast4();
                BigDecimal mag;
                switch (st.p.sign()) {
                    case SEPARATE_COLUMNS -> {
                        boolean isDebit = tr.debit() != null;
                        mag = isDebit ? tr.debit() : tr.credit();
                        r.sign = isDebit ? -1 : 1;
                        r.source = "column";
                    }
                    case SUFFIX -> {
                        mag = tr.amount();
                        String m = tr.marker();
                        if (m != null && m.equalsIgnoreCase(st.p.suffix().creditToken())) {
                            r.sign = 1;
                            r.source = "suffix";
                        } else if (m != null && m.equalsIgnoreCase(st.p.suffix().debitToken())) {
                            r.sign = -1;
                            r.source = "suffix";
                        } else {
                            r.sign = st.p.suffix().defaultDirection() == Direction.CREDIT ? 1 : -1;
                            r.source = "default";
                        }
                    }
                    case PREFIX -> {
                        mag = tr.amount();
                        String m = tr.marker();
                        if (m != null && st.p.prefix().credit().matcher(m).matches()) {
                            r.sign = 1;
                            r.source = "prefix";
                        } else if (m != null && st.p.prefix().debit() != null && st.p.prefix().debit().matcher(m).matches()) {
                            r.sign = -1;
                            r.source = "prefix";
                        } else {
                            r.sign = st.p.prefix().defaultDirection() == Direction.CREDIT ? 1 : -1;
                            r.source = "default";
                        }
                    }
                    case MINUS_IS_CREDIT -> {
                        mag = tr.amount();
                        r.sign = tr.amountNegativeMarked() ? 1 : -1;
                        r.source = "minus";
                    }
                    default -> {
                        mag = tr.amount();
                        r.source = "balance";
                    }
                }
                if (mag == null || tr.date() == null) {
                    st.dropped++;
                    continue;
                }
                r.magnitude = mag.abs();
                out.add(r);
            }
        }
        return out;
    }

    /**
     * Rows with no section header above them take the card's last 4 from the header region: group 1 of the declared
     * regex over the lines before the first table header, else the last 4 digits of the ACCOUNT_NUMBER value.
     */
    private static void applyHeaderRegionLast4(State st, List<Raw> raws, StatementMeta meta) {
        LayoutProfile p = st.p;
        if (p.cardLast4From() == null && !p.cardLast4FromAccountNumber()) {
            return;
        }
        String last4 = null;
        if (p.cardLast4From() != null) {
            for (int i = 0; i < st.lines.size() && last4 == null; i++) {
                if (st.kind[i] == Kind.HEADER) {
                    break;
                }
                Matcher m = p.cardLast4From().matcher(st.lines.get(i).text());
                if (m.find() && m.group(1) != null) {
                    last4 = lastFourDigits(m.group(1));
                }
            }
        }
        if (last4 == null && p.cardLast4FromAccountNumber() && meta.accountNumber != null) {
            last4 = lastFourDigits(meta.accountNumber);
        }
        if (last4 != null) {
            for (Raw r : raws) {
                if (r.cardLast4 == null) {
                    r.cardLast4 = last4;
                }
            }
        }
    }

    private static String lastFourDigits(String text) {
        String digits = text.replaceAll("[^0-9]", "");
        return digits.length() >= 4 ? digits.substring(digits.length() - 4) : null;
    }

    private static void orderRows(List<Raw> rows, LayoutProfile p) {
        boolean reverse = false;
        if (p.order() == LayoutProfile.Order.DESC) {
            reverse = true;
        } else if (p.order() == LayoutProfile.Order.AUTO) {
            int asc = 0;
            int desc = 0;
            for (int i = 0; i + 1 < rows.size(); i++) {
                LocalDate a = rows.get(i).date;
                LocalDate b = rows.get(i + 1).date;
                if (b.isAfter(a)) {
                    asc++;
                } else if (b.isBefore(a)) {
                    desc++;
                }
            }
            reverse = desc > asc;
        }
        if (reverse) {
            Collections.reverse(rows);
        }
    }

    // ---- summary ----------------------------------------------------------------------------------------------

    private static void harvestSummary(State st, StatementMeta meta, LinkedHashMap<String, Object> fields) {
        for (Summary s : st.p.summary()) {
            Object v = findValue(st, s);
            if (v == null) {
                continue;
            }
            if (s.metaTarget() != null) {
                switch (s.metaTarget()) {
                    case PERIOD -> {
                        LocalDate[] range = (LocalDate[]) v;
                        meta.periodStart = range[0];
                        meta.periodEnd = range[1];
                        st.periodStart = range[0];
                        st.periodEnd = range[1];
                    }
                    case PERIOD_START -> {
                        meta.periodStart = (LocalDate) v;
                        st.periodStart = (LocalDate) v;
                    }
                    case PERIOD_END -> {
                        meta.periodEnd = (LocalDate) v;
                        st.periodEnd = (LocalDate) v;
                    }
                    case STATEMENT_DATE -> meta.documentDate = (LocalDate) v;
                    case ACCOUNT_NUMBER -> meta.accountNumber = (String) v;
                    case OPENING_BALANCE -> meta.openingBalance = ((BigDecimal) v).doubleValue();
                    case CLOSING_BALANCE -> meta.closingBalance = ((BigDecimal) v).doubleValue();
                }
            } else if (v instanceof BigDecimal bd) {
                fields.put(s.legacyKey(), bd.doubleValue());
            } else {
                fields.put(s.legacyKey(), v);
            }
        }
    }

    private static Object findValue(State st, Summary s) {
        for (int i = 0; i < st.lines.size(); i++) {
            if (!st.scannable[i]) {
                continue;
            }
            Line ln = st.lines.get(i);
            if (ln.page() > st.summaryMaxPage) {
                continue;
            }
            if (!s.pages().isEmpty() && !s.pages().contains(ln.page())) {
                continue;
            }
            Spans sp = spans(ln);
            Matcher m = s.label().matcher(sp.text());
            if (!m.find()) {
                continue;
            }
            double lx0 = Double.MAX_VALUE;
            double lx1 = -Double.MAX_VALUE;
            for (int k = 0; k < ln.words().size(); k++) {
                int[] rg = sp.ranges().get(k);
                if (rg[1] > m.start() && rg[0] < m.end()) {
                    lx0 = Math.min(lx0, ln.words().get(k).x0());
                    lx1 = Math.max(lx1, ln.words().get(k).x1());
                }
            }
            Object v = null;
            if (s.position() == Position.CAPTURE) {
                v = capture(st, s, i);
            } else if (s.position() == Position.RIGHT_ON_LINE) {
                List<Word> right = new ArrayList<>();
                for (Word w : ln.words()) {
                    if (w.x0() >= lx1 - 2) {
                        right.add(w);
                    }
                }
                v = value(st, s, right, ln.words(), Double.NaN);
            } else {
                int seen = 0;
                for (int j = i + 1; j < st.lines.size() && seen < s.maxLines(); j++) {
                    Line below = st.lines.get(j);
                    if (below.page() != ln.page()) {
                        break;
                    }
                    seen++;
                    if (s.position() == Position.NTH_AMOUNT_BELOW) {
                        List<Word> amounts = new ArrayList<>();
                        for (Word w : below.words()) {
                            if (Amounts.isAmount(w.text())) {
                                amounts.add(w);
                            }
                        }
                        if (amounts.isEmpty()) {
                            continue;
                        }
                        v = amounts.size() >= s.n() ? amountValue(s, amounts.get(s.n() - 1), below.words()) : null;
                        break;
                    }
                    List<Word> window = new ArrayList<>();
                    for (Word w : below.words()) {
                        if (w.x1() > lx0 - s.xTolerance() && w.x0() < lx1 + s.xTolerance()) {
                            window.add(w);
                        }
                    }
                    v = value(st, s, window, below.words(), (lx0 + lx1) / 2);
                    if (v != null) {
                        break;
                    }
                }
            }
            if (v != null) {
                return v;
            }
        }
        return null;
    }

    /** Group 1 of the capture regex over the label line (then the next captureLines lines of the page), parsed by the type. */
    private static Object capture(State st, Summary s, int labelLine) {
        Line first = st.lines.get(labelLine);
        for (int j = labelLine; j < st.lines.size() && j <= labelLine + s.captureLines(); j++) {
            Line ln = st.lines.get(j);
            if (ln.page() != first.page()) {
                break;
            }
            Matcher m = s.capture().matcher(spans(ln).text());
            if (m.find()) {
                Object v = parseCaptured(st, s, m);
                if (v != null) {
                    return v;
                }
            }
        }
        return null;
    }

    private static final Pattern CAPTURED_SUFFIX = Pattern.compile("^(.*\\S)\\s+(?i:cr|dr)$");

    private static Object parseCaptured(State st, Summary s, Matcher m) {
        String raw = m.group(1);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        raw = raw.strip();
        switch (s.type()) {
            case AMOUNT: {
                String token = null;
                Amounts.Parsed a = Amounts.parse(raw);
                if (a == null) {
                    Matcher sm = CAPTURED_SUFFIX.matcher(raw);
                    if (sm.matches()) {
                        a = Amounts.parse(sm.group(1));
                        token = raw.substring(sm.end(1)).strip().toLowerCase(Locale.ROOT);
                    }
                }
                return a == null ? null : creditValue(s, a, token != null ? token : a.suffix());
            }
            case DATE:
                return st.dates.parse(raw, st.periodStart, st.periodEnd);
            case DATE_RANGE: {
                LocalDate d1;
                LocalDate d2;
                if (m.groupCount() >= 2 && m.group(2) != null) {
                    d1 = st.dates.parse(raw, st.periodStart, st.periodEnd);
                    d2 = st.dates.parse(m.group(2).strip(), st.periodStart, st.periodEnd);
                } else {
                    List<Word> words = new ArrayList<>();
                    for (String t : raw.split("\\s+")) {
                        words.add(new Word(t, 0, 0, 0, 0));
                    }
                    List<LocalDate> ds = datesIn(st, words);
                    d1 = ds.size() >= 2 ? ds.get(0) : null;
                    d2 = ds.size() >= 2 ? ds.get(ds.size() - 1) : null;
                }
                return d1 != null && d2 != null ? new LocalDate[]{d1, d2} : null;
            }
            case MASKED_NUMBER:
                return s.compact() ? raw.replaceAll("\\s+", "") : raw;
            case INTEGER: {
                String t = Amounts.norm(raw);
                return INTEGER.matcher(t).matches() ? new BigDecimal(t.replace(",", "")) : null;
            }
            default:
                return null;
        }
    }

    private static final Pattern MASKED = Pattern.compile("^[0-9]*[*xX•]+[0-9*xX•]*[0-9]{2,}$");
    private static final Pattern INTEGER = Pattern.compile("^(?:\\d{1,3}(?:,\\d{2,3})+|\\d+)$");

    private static Object value(State st, Summary s, List<Word> candidates, List<Word> lineWords, double centre) {
        ValueType type = s.type();
        switch (type) {
            case AMOUNT: {
                Word best = null;
                for (Word w : candidates) {
                    if (!Amounts.isAmount(w.text())) {
                        continue;
                    }
                    if (Double.isNaN(centre)) {
                        best = w;
                        break;
                    }
                    if (best == null || Math.abs((w.x0() + w.x1()) / 2 - centre)
                            < Math.abs((best.x0() + best.x1()) / 2 - centre)) {
                        best = w;
                    }
                }
                return best == null ? null : amountValue(s, best, lineWords);
            }
            case DATE: {
                List<LocalDate> ds = datesIn(st, candidates);
                return ds.isEmpty() ? null : ds.get(0);
            }
            case DATE_RANGE: {
                List<LocalDate> ds = datesIn(st, candidates);
                return ds.size() >= 2 ? new LocalDate[]{ds.get(0), ds.get(ds.size() - 1)} : null;
            }
            case MASKED_NUMBER:
                for (Word w : candidates) {
                    if (MASKED.matcher(Amounts.norm(w.text())).matches()) {
                        return Amounts.norm(w.text());
                    }
                }
                return null;
            case INTEGER:
                for (Word w : candidates) {
                    String t = Amounts.norm(w.text());
                    if (INTEGER.matcher(t).matches()) {
                        return new BigDecimal(t.replace(",", ""));
                    }
                }
                return null;
            default:
                return null;
        }
    }

    /** The amount word's value; a Cr token (glued or the next word) or a minus marks a credit: negative, zero or plain. */
    private static BigDecimal amountValue(Summary s, Word w, List<Word> lineWords) {
        Amounts.Parsed a = Amounts.parse(w.text());
        String token = a.suffix();
        if (token == null) {
            int idx = lineWords.indexOf(w);
            if (idx >= 0 && idx + 1 < lineWords.size()) {
                String nxt = Amounts.norm(lineWords.get(idx + 1).text()).toLowerCase(Locale.ROOT);
                if (nxt.equals("cr") || nxt.equals("dr")) {
                    token = nxt;
                }
            }
        }
        return creditValue(s, a, token);
    }

    private static BigDecimal creditValue(Summary s, Amounts.Parsed a, String token) {
        boolean credit = a.negativeMarked() || (token != null && token.equalsIgnoreCase("cr"));
        if (credit && s.creditIsZero()) {
            return BigDecimal.ZERO;
        }
        return credit && s.creditIsNegative() ? a.value().negate() : a.value();
    }

    private static List<LocalDate> datesIn(State st, List<Word> words) {
        List<LocalDate> found = new ArrayList<>();
        int i = 0;
        while (i < words.size()) {
            boolean hit = false;
            for (int span = Math.min(3, words.size() - i); span >= 1; span--) {
                StringBuilder sb = new StringBuilder();
                for (int k = i; k < i + span; k++) {
                    if (sb.length() > 0) {
                        sb.append(' ');
                    }
                    sb.append(Amounts.norm(words.get(k).text()));
                }
                LocalDate d = st.dates.parse(sb.toString(), st.periodStart, st.periodEnd);
                if (d != null) {
                    found.add(d);
                    i += span;
                    hit = true;
                    break;
                }
            }
            if (!hit) {
                i++;
            }
        }
        return found;
    }
}
