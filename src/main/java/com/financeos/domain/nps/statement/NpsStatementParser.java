package com.financeos.domain.nps.statement;

import com.financeos.domain.nps.statement.NpsWords.Cell;
import com.financeos.domain.nps.statement.NpsWords.Line;
import com.financeos.domain.nps.statement.NpsWords.Word;
import com.financeos.domain.nps.statement.ParsedNpsStatement.ContributionRow;
import com.financeos.domain.nps.statement.ParsedNpsStatement.Holding;
import com.financeos.domain.nps.statement.ParsedNpsStatement.Layout;
import com.financeos.domain.nps.statement.ParsedNpsStatement.Pref;
import com.financeos.domain.nps.statement.ParsedNpsStatement.Summary;
import com.financeos.domain.nps.statement.ParsedNpsStatement.Txn;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.springframework.stereotype.Component;

/**
 * Pure parser for the Protean (NSDL) CRA "NPS Transaction Statement" PDF. Handles the WIDE layout (schemes as
 * columns, up to Sep 2026) and the STACKED layout (one block per scheme, Oct 2026 onwards). No I/O beyond the
 * given bytes, and nothing about the statement is logged.
 */
@Component
public final class NpsStatementParser {

    private static final Pattern NUM = Pattern.compile("^\\(?-?\\d[\\d,]*(?:\\.\\d+)?\\)?$");
    private static final Pattern SHORT_DATE = Pattern.compile("^(\\d{1,2})-([A-Za-z]{3})-(\\d{2})$");
    private static final Pattern PERIOD = Pattern.compile(
            "([A-Za-z]{3})\\s+(\\d{1,2}),\\s*(\\d{4})\\s+To\\s+([A-Za-z]{3})\\s+(\\d{1,2}),\\s*(\\d{4})");
    private static final Pattern PERCENT = Pattern.compile("^-?\\d+(?:\\.\\d+)?%$");
    private static final Pattern PRAN = Pattern.compile("^\\d{12}$");
    private static final Pattern FURNITURE = Pattern.compile(
            "(?i)^(?:NPS TRANSACTION STATEMENT|Statement Generation Date.*|Page \\d+.*|"
                    + "[A-Za-z]{3} \\d{1,2}, \\d{4} To [A-Za-z]{3} \\d{1,2}, \\d{4})$");
    private static final Pattern TIER_LABEL = Pattern.compile(
            "^Tier\\s+\\S+(?:\\s+(?:Tax Saver|Virtual Account))?(\\s+Status)?$");
    private static final List<String> MONTHS =
            List.of("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec");
    private static final Set<String> TABLE_HEADER_WORDS = Set.of("Amount", "(Rs)", "NAV", "Units", "Date", "Particulars",
            "Description");
    private static final double HEADER_GAP = 15.0;
    private static final double HEADER_DEPTH = 45.0;

    /** Throws NpsStatementLockedException if none of the passwords opens it; NpsStatementFormatException if it is not an NPS statement. */
    public ParsedNpsStatement parse(byte[] pdf, List<String> passwords) {
        List<Line> all;
        try (PDDocument doc = openWithAny(pdf, passwords)) {
            all = NpsWords.lines(doc);
        } catch (IOException e) {
            throw new NpsStatementFormatException("This file could not be read as an NPS statement.", e);
        }
        return new Run(all).parse();
    }

    /** True only when the PDF is password protected and {@code password} is the one that opens it. */
    public boolean passwordOpens(byte[] pdf, String password) {
        return NpsWords.unlockedBy(pdf, password);
    }

    private static PDDocument openWithAny(byte[] pdf, List<String> passwords) throws IOException {
        List<String> tries = new ArrayList<>();
        if (passwords != null) {
            for (String p : passwords) {
                if (p != null && !p.isEmpty()) {
                    tries.add(p);
                }
            }
        }
        if (tries.isEmpty()) {
            tries.add(null);
        }
        for (String p : tries) {
            PDDocument doc;
            try {
                doc = NpsWords.open(pdf, p);
            } catch (IOException e) {
                String blob = String.valueOf(e.getMessage()).toLowerCase(Locale.ROOT);
                if (blob.contains("password") || blob.contains("decrypt")) {
                    continue;
                }
                throw e;
            }
            if (doc != null) {
                return doc;
            }
        }
        throw new NpsStatementLockedException("The NPS statement is password protected and the password did not match.");
    }

    // ---- helpers -------------------------------------------------------------------------------------------

    static boolean isNum(String s) {
        return NUM.matcher(s).matches();
    }

    static BigDecimal num(String s, int scale) {
        String t = s.trim();
        boolean neg = false;
        if (t.startsWith("(") && t.endsWith(")")) {
            neg = true;
            t = t.substring(1, t.length() - 1);
        }
        if (t.startsWith("-")) {
            neg = !neg;
            t = t.substring(1);
        }
        BigDecimal v = new BigDecimal(t.replace(",", "")).setScale(scale, RoundingMode.HALF_UP);
        return neg ? v.negate() : v;
    }

    static LocalDate shortDate(String s) {
        Matcher m = SHORT_DATE.matcher(s);
        if (!m.matches()) {
            return null;
        }
        int mi = MONTHS.indexOf(m.group(2).toLowerCase(Locale.ROOT));
        if (mi < 0) {
            return null;
        }
        try {
            return LocalDate.of(2000 + Integer.parseInt(m.group(3)), mi + 1, Integer.parseInt(m.group(1)));
        } catch (DateTimeException e) {
            return null;
        }
    }

    static NpsTxnKind kindOf(String description) {
        String d = description.toLowerCase(Locale.ROOT);
        if (d.contains("opening")) {
            return NpsTxnKind.OPENING;
        }
        if (d.contains("closing")) {
            return NpsTxnKind.CLOSING;
        }
        if (d.contains("billing for")) {
            return NpsTxnKind.CHARGE;
        }
        if (d.contains("rebalancing")) {
            return NpsTxnKind.REBALANCE;
        }
        if (d.contains("multiple nav framework") || d.contains("credit of units due to implementation")) {
            return NpsTxnKind.MIGRATION;
        }
        if (d.contains("switch")) {
            return NpsTxnKind.SWITCH;
        }
        if (d.contains("withdrawal") || d.contains("redemption") || d.contains("exit")) {
            return NpsTxnKind.WITHDRAWAL;
        }
        if (d.contains("employer")) {
            return NpsTxnKind.EMPLOYER_CONTRIBUTION;
        }
        if (d.contains("contribution")) {
            return NpsTxnKind.CONTRIBUTION;
        }
        return NpsTxnKind.OTHER;
    }

    private static String collapse(String s) {
        return s.trim().replaceAll("\\s+", " ");
    }

    // ---- one parse -----------------------------------------------------------------------------------------

    private static final class Run {
        final List<Line> raw;
        final List<Line> lines = new ArrayList<>();
        final List<String> warnings = new ArrayList<>();
        Layout layout;
        LocalDate periodStart;
        LocalDate periodEnd;

        Run(List<Line> raw) {
            this.raw = raw;
        }

        ParsedNpsStatement parse() {
            boolean sawTitle = false;
            for (Line l : raw) {
                String t = l.text();
                if (t.equalsIgnoreCase("NPS TRANSACTION STATEMENT")) {
                    sawTitle = true;
                }
                Matcher pm = PERIOD.matcher(t);
                if (periodStart == null && pm.find()) {
                    periodStart = longDate(pm.group(1), pm.group(2), pm.group(3));
                    periodEnd = longDate(pm.group(4), pm.group(5), pm.group(6));
                }
            }
            for (Line l : raw) {
                String t = l.text();
                if (t.startsWith("Note:")) {
                    break;
                }
                if (!FURNITURE.matcher(t).matches()) {
                    lines.add(l);
                }
            }
            String pran = pran();
            if (!sawTitle || pran == null || periodStart == null || periodEnd == null) {
                throw new NpsStatementFormatException("This file is not an NPS transaction statement.");
            }

            int summaryStart = indexOf(l -> l.text().toLowerCase(Locale.ROOT).contains("scheme wise summary"), 0);
            int changesStart = indexOf(l -> l.text().startsWith("Changes made"), Math.max(summaryStart, 0));
            int contribStart = indexOf(l -> l.text().startsWith("Contribution/Redemption Details"), 0);
            int txnStart = indexOf(l -> l.text().equals("Transaction Details"), 0);
            layout = detectLayout(summaryStart, changesStart);

            List<Holding> holdings = layout == Layout.WIDE
                    ? wideHoldings(summaryStart, changesStart) : stackedHoldings(summaryStart, changesStart);
            List<ContributionRow> contributions = contributions(contribStart, txnStart);
            List<Txn> txns = txnStart < 0 ? List.of() : transactions(txnStart + 1, lines.size());

            Map<String, String> tiers = new LinkedHashMap<>();
            String name = header(tiers);
            return new ParsedNpsStatement(pran, name, periodStart, periodEnd, layout, tiers, choice(), prefs(),
                    summary(), holdings, contributions, txns, List.copyOf(warnings));
        }

        // -- header --

        String pran() {
            for (Line l : lines) {
                List<Word> ws = l.words();
                for (int i = 0; i + 1 < ws.size(); i++) {
                    if (ws.get(i).text().equals("PRAN") && PRAN.matcher(ws.get(i + 1).text()).matches()) {
                        return ws.get(i + 1).text();
                    }
                }
            }
            return null;
        }

        String header(Map<String, String> tiers) {
            String name = null;
            int end = indexOf(l -> l.text().startsWith("Current Scheme Preference"), 0);
            if (end < 0) {
                end = lines.size();
            }
            for (int i = 0; i < end; i++) {
                List<Cell> cells = lines.get(i).cells();
                if (name == null && cells.size() >= 2 && cells.get(0).text().equals("Subscriber Name")) {
                    name = collapse(cells.get(1).text());
                }
                for (int c = 0; c + 1 < cells.size(); c++) {
                    Cell label = cells.get(c);
                    Matcher m = TIER_LABEL.matcher(label.text());
                    if (!m.matches()) {
                        continue;
                    }
                    String key = label.text();
                    if (m.group(1) == null) {
                        boolean wrapped = i + 1 < end && samePage(i, i + 1) && lines.get(i + 1).cells().stream()
                                .anyMatch(x -> x.text().equals("Status") && Math.abs(x.x0() - label.x0()) < 4);
                        if (!wrapped) {
                            continue;
                        }
                        key = key + " Status";
                    }
                    tiers.put(key, cells.get(c + 1).text().trim().toUpperCase(Locale.ROOT));
                }
            }
            return name;
        }

        String choice() {
            Pattern p = Pattern.compile("Scheme Choice\\s*:\\s*(.+)$");
            for (Line l : lines) {
                Matcher m = p.matcher(l.text());
                if (m.find()) {
                    return collapse(m.group(1));
                }
            }
            return null;
        }

        List<Pref> prefs() {
            List<Pref> out = new ArrayList<>();
            int start = indexOf(l -> l.text().startsWith("Investment Option"), 0);
            int end = indexOf(l -> l.text().equals("Investment Summary"), Math.max(start, 0));
            if (start < 0 || end < 0) {
                return out;
            }
            StringBuilder name = null;
            BigDecimal pct = null;
            for (int i = start + 1; i < end; i++) {
                List<Word> ws = lines.get(i).words();
                boolean head = ws.size() > 2 && ws.get(0).text().equals("Scheme") && ws.get(1).text().matches("\\d+");
                if (head) {
                    if (name != null) {
                        out.add(new Pref(collapse(name.toString()), pct));
                    }
                    name = new StringBuilder();
                    pct = null;
                    for (int w = 2; w < ws.size(); w++) {
                        String t = ws.get(w).text();
                        if (PERCENT.matcher(t).matches()) {
                            pct = num(t.substring(0, t.length() - 1), 2);
                        } else {
                            name.append(' ').append(t);
                        }
                    }
                } else if (name != null) {
                    for (Word w : ws) {
                        if (PERCENT.matcher(w.text()).matches()) {
                            pct = num(w.text().substring(0, w.text().length() - 1), 2);
                        } else {
                            name.append(' ').append(w.text());
                        }
                    }
                }
            }
            if (name != null) {
                out.add(new Pref(collapse(name.toString()), pct));
            }
            return out;
        }

        Summary summary() {
            int start = indexOf(l -> l.text().equals("Investment Summary"), 0);
            if (start < 0) {
                warnings.add("Investment summary missing");
                return null;
            }
            int end = indexOf(l -> l.text().toLowerCase(Locale.ROOT).contains("scheme wise summary"), start);
            if (end < 0) {
                end = lines.size();
            }
            BigDecimal xirr = null;
            int aLine = -1;
            for (int i = start + 1; i < end; i++) {
                for (Word w : lines.get(i).words()) {
                    if (xirr == null && PERCENT.matcher(w.text()).matches()) {
                        xirr = num(w.text().substring(0, w.text().length() - 1), 2);
                    }
                }
                if (aLine < 0 && lines.get(i).words().stream().anyMatch(w -> w.text().equals("(A)"))) {
                    aLine = i;
                }
            }
            List<String> nums = new ArrayList<>();
            for (int i = aLine < 0 ? end : aLine + 1; i < end && nums.size() < 5; i++) {
                for (Word w : lines.get(i).words()) {
                    if (isNum(w.text())) {
                        nums.add(w.text());
                    }
                }
            }
            if (nums.size() < 5) {
                warnings.add("Investment summary values missing");
                return null;
            }
            BigDecimal charges = layout == Layout.WIDE && nums.size() >= 6 ? num(nums.get(5), 2) : null;
            return new Summary(num(nums.get(0), 2), num(nums.get(1), 0).intValue(), num(nums.get(2), 2),
                    num(nums.get(3), 2), num(nums.get(4), 2), charges, xirr);
        }

        // -- scheme-wise summary --

        Layout detectLayout(int from, int to) {
            if (from >= 0) {
                for (int i = from + 1; i < (to < 0 ? lines.size() : to); i++) {
                    List<Word> ws = lines.get(i).words();
                    if (ws.size() >= 2 && ws.get(0).text().equals("Total") && ws.get(1).text().equals("Units")) {
                        return Layout.WIDE;
                    }
                }
            }
            return Layout.STACKED;
        }

        List<Holding> wideHoldings(int from, int to) {
            List<Holding> out = new ArrayList<>();
            if (from < 0) {
                warnings.add("Scheme-wise holdings missing");
                return out;
            }
            int end = to < 0 ? lines.size() : to;
            List<Integer> numLines = new ArrayList<>();
            int headEnd = end;
            for (int i = from + 1; i < end; i++) {
                List<Word> ws = lines.get(i).words();
                boolean label = ws.size() >= 2 && ws.get(0).text().equals("Scheme") && ws.get(1).text().equals("wise");
                boolean hasNum = ws.stream().anyMatch(w -> isNum(w.text()));
                if ((label || hasNum) && headEnd == end) {
                    headEnd = i;
                }
                if (hasNum) {
                    numLines.add(i);
                }
            }
            if (numLines.isEmpty()) {
                warnings.add("Scheme-wise holdings missing");
                return out;
            }
            List<Word> valueNums = numbers(lines.get(numLines.get(0)));
            int cols = valueNums.size();
            List<StringBuilder> names = new ArrayList<>();
            for (int c = 0; c < cols; c++) {
                names.add(new StringBuilder());
            }
            for (int i = from + 1; i < headEnd; i++) {
                for (Word w : lines.get(i).words()) {
                    if (w.text().equals("Particulars") || w.text().equals("References")) {
                        continue;
                    }
                    names.get(nearest(valueNums, w.center())).append(' ').append(w.text());
                }
            }
            LocalDate navDate = periodEnd;
            for (int i = from + 1; i < end; i++) {
                if (lines.get(i).text().startsWith("NAV as on")) {
                    for (Word w : lines.get(i).words()) {
                        LocalDate d = shortDate(w.text());
                        if (d != null) {
                            navDate = d;
                        }
                    }
                }
            }
            BigDecimal[] values = column(valueNums, valueNums, 2);
            BigDecimal[] units = numLines.size() > 1 ? column(numbers(lines.get(numLines.get(1))), valueNums, 4) : new BigDecimal[cols];
            BigDecimal[] navs = numLines.size() > 2 ? column(numbers(lines.get(numLines.get(2))), valueNums, 4) : new BigDecimal[cols];
            if (numLines.size() < 3) {
                warnings.add("Scheme-wise units or NAV missing");
            }
            for (int c = 0; c < cols; c++) {
                out.add(new Holding(collapse(names.get(c).toString()), units[c], navs[c], navDate, values[c]));
            }
            return out;
        }

        List<Holding> stackedHoldings(int from, int to) {
            List<Holding> out = new ArrayList<>();
            if (from < 0) {
                warnings.add("Scheme-wise holdings missing");
                return out;
            }
            int end = to < 0 ? lines.size() : to;
            StringBuilder name = null;
            BigDecimal value = null;
            BigDecimal units = null;
            BigDecimal nav = null;
            for (int i = from + 1; i < end; i++) {
                List<Word> ws = lines.get(i).words();
                List<Word> nums = numbers(lines.get(i));
                if (nums.size() >= 3) {
                    if (name != null) {
                        out.add(new Holding(collapse(name.toString()), units, nav, periodEnd, value));
                    }
                    int n = nums.size();
                    value = num(nums.get(n - 3).text(), 2);
                    units = num(nums.get(n - 2).text(), 4);
                    nav = num(nums.get(n - 1).text(), 4);
                    name = new StringBuilder();
                    double firstNum = nums.get(n - 3).x0();
                    for (Word w : ws) {
                        if (w.x0() < firstNum - 0.5) {
                            name.append(' ').append(w.text());
                        }
                    }
                } else if (name != null) {
                    for (Word w : ws) {
                        name.append(' ').append(w.text());
                    }
                }
            }
            if (name != null) {
                out.add(new Holding(collapse(name.toString()), units, nav, periodEnd, value));
            }
            if (out.isEmpty()) {
                warnings.add("Scheme-wise holdings missing");
            }
            return out;
        }

        // -- contributions --

        List<ContributionRow> contributions(int from, int to) {
            List<ContributionRow> out = new ArrayList<>();
            if (from < 0) {
                return out;
            }
            int end = to < 0 ? lines.size() : to;
            double uploadedX = Double.NaN;
            for (int i = from + 1; i < end; i++) {
                for (Word w : lines.get(i).words()) {
                    if (w.text().equals("Uploaded") && Double.isNaN(uploadedX)) {
                        uploadedX = w.x0();
                    }
                }
            }
            for (int i = from + 1; i < end; i++) {
                List<Word> ws = lines.get(i).words();
                LocalDate date = ws.isEmpty() ? null : shortDate(ws.get(0).text());
                if (date == null) {
                    continue;
                }
                List<Word> nums = numbers(lines.get(i));
                if (nums.size() < 3) {
                    warnings.add("Contribution amounts missing for a row");
                    continue;
                }
                int n = nums.size();
                double firstNum = nums.get(n - 3).x0();
                StringBuilder particulars = new StringBuilder();
                StringBuilder uploaded = new StringBuilder();
                List<Word> mid = new ArrayList<>();
                for (int w = 1; w < ws.size(); w++) {
                    if (ws.get(w).x0() < firstNum - 0.5) {
                        mid.add(ws.get(w));
                    }
                }
                List<Cell> cells = new Line(mid).cells();
                if (cells.size() >= 2) {
                    for (int c = 0; c < cells.size(); c++) {
                        (c == 0 ? particulars : uploaded).append(' ').append(cells.get(c).text());
                    }
                } else {
                    for (Word w : mid) {
                        (!Double.isNaN(uploadedX) && w.x0() >= uploadedX - 4 ? uploaded : particulars)
                                .append(' ').append(w.text());
                    }
                }
                out.add(new ContributionRow(date, collapse(particulars.toString()),
                        uploaded.length() == 0 ? null : collapse(uploaded.toString()),
                        num(nums.get(n - 3).text(), 2), num(nums.get(n - 2).text(), 2),
                        num(nums.get(n - 1).text(), 2)));
            }
            return out;
        }

        // -- transactions --

        /** One scheme's column zones (x ranges by word centre). NaN navLo means NAV shares the amount zone. */
        private static final class Group {
            String name = "";
            double amtLo;
            double navLo = Double.NaN;
            double unitsLo;
            double hi = Double.POSITIVE_INFINITY;
        }

        private static final class Table {
            final List<Group> groups = new ArrayList<>();
            double leftHi;
        }

        private static final class Row {
            final LocalDate date;
            final StringBuilder description = new StringBuilder();
            final List<BigDecimal> amount = new ArrayList<>();
            final List<BigDecimal> nav = new ArrayList<>();
            final List<BigDecimal> units = new ArrayList<>();
            BigDecimal chargeTotal;

            Row(LocalDate date, int groups) {
                this.date = date;
                for (int i = 0; i < groups; i++) {
                    amount.add(null);
                    nav.add(null);
                    units.add(null);
                }
            }
        }

        List<Txn> transactions(int from, int end) {
            List<Txn> out = new ArrayList<>();
            List<Integer> dateHeaders = new ArrayList<>();
            for (int i = from; i < end; i++) {
                if (isDateHeader(lines.get(i))) {
                    dateHeaders.add(i);
                }
            }
            if (dateHeaders.isEmpty()) {
                return out;
            }
            // header ranges
            Map<Integer, int[]> ranges = new LinkedHashMap<>();
            Set<Integer> upLines = new LinkedHashSet<>();
            for (int d : dateHeaders) {
                int hs = d;
                while (hs - 1 >= from && headerLike(hs - 1, hs)) {
                    hs--;
                    upLines.add(hs);
                }
                ranges.put(d, new int[] {hs, d});
            }
            for (int d : dateHeaders) {
                int he = d;
                while (he + 1 < end && !upLines.contains(he + 1) && headerBelow(he + 1, d)) {
                    he++;
                }
                ranges.get(d)[1] = he;
            }
            Set<Integer> headerIdx = new LinkedHashSet<>();
            for (int[] r : ranges.values()) {
                for (int i = r[0]; i <= r[1]; i++) {
                    headerIdx.add(i);
                }
            }

            Table table = null;
            Row row = null;
            Set<String> missingAmounts = new LinkedHashSet<>();
            for (int i = from; i < end; i++) {
                if (headerIdx.contains(i)) {
                    if (ranges.containsKey(i)) {
                        if (row != null) {
                            flush(row, table, out, missingAmounts);
                            row = null;
                        }
                        int[] r = ranges.get(i);
                        table = layout == Layout.WIDE ? wideTable(r[0], r[1], i) : stackedTable(r[0], i);
                        if (table == null) {
                            warnings.add("Transaction columns could not be read");
                        }
                    }
                    continue;
                }
                if (table == null) {
                    continue;
                }
                Line line = lines.get(i);
                List<Word> ws = line.words();
                LocalDate date = ws.isEmpty() ? null : shortDate(ws.get(0).text());
                boolean first = date != null;
                if (first) {
                    if (row != null) {
                        flush(row, table, out, missingAmounts);
                    }
                    row = new Row(date, table.groups.size());
                }
                if (row == null) {
                    continue;
                }
                for (int w = first ? 1 : 0; w < ws.size(); w++) {
                    place(row, table, ws.get(w), first);
                }
            }
            if (row != null) {
                flush(row, table, out, missingAmounts);
            }
            if (!missingAmounts.isEmpty()) {
                warnings.add("Transaction amounts missing for " + missingAmounts.size() + " schemes");
            }
            return out;
        }

        private void place(Row row, Table table, Word w, boolean firstLine) {
            double c = w.center();
            boolean isNumber = isNum(w.text());
            if (c < table.groups.get(0).amtLo) {
                if (firstLine || !isNumber) {
                    row.description.append(' ').append(w.text());
                } else if (layout == Layout.WIDE) {
                    row.chargeTotal = num(w.text(), 2);
                }
                return;
            }
            if (!isNumber) {
                return;
            }
            for (int g = 0; g < table.groups.size(); g++) {
                Group grp = table.groups.get(g);
                if (c < grp.amtLo || c >= grp.hi) {
                    continue;
                }
                if (c >= grp.unitsLo) {
                    row.units.set(g, num(w.text(), 4));
                } else if (Double.isNaN(grp.navLo)) {
                    if (firstLine) {
                        row.amount.set(g, num(w.text(), 2));
                    } else {
                        row.nav.set(g, num(w.text(), 4));
                    }
                } else if (c >= grp.navLo) {
                    row.nav.set(g, num(w.text(), 4));
                } else {
                    row.amount.set(g, num(w.text(), 2));
                }
                return;
            }
        }

        private void flush(Row row, Table table, List<Txn> out, Set<String> missingAmounts) {
            String description = collapse(row.description.toString());
            NpsTxnKind kind = kindOf(description);
            boolean bookend = kind == NpsTxnKind.OPENING || kind == NpsTxnKind.CLOSING;
            for (int g = 0; g < table.groups.size(); g++) {
                BigDecimal amount = row.amount.get(g);
                BigDecimal nav = row.nav.get(g);
                BigDecimal units = row.units.get(g);
                if (amount == null && nav == null && units == null) {
                    continue;
                }
                String name = table.groups.get(g).name;
                if (bookend) {
                    amount = null;
                } else if (amount == null) {
                    missingAmounts.add(name);
                }
                out.add(new Txn(name, row.date, description, kind, amount, nav, units, row.chargeTotal));
            }
        }

        private boolean isDateHeader(Line l) {
            List<Word> ws = l.words();
            if (ws.isEmpty() || !ws.get(0).text().equals("Date")) {
                return false;
            }
            return ws.stream().anyMatch(w -> w.text().equals("Particulars") || w.text().equals("Description"));
        }

        /** True when line {@code i} sits right next to line {@code ref} and is a text-only (header-style) line. */
        private boolean headerLike(int i, int ref) {
            if (!samePage(Math.min(i, ref), Math.max(i, ref))) {
                return false;
            }
            Line l = lines.get(i);
            if (Math.abs(l.top() - lines.get(ref).top()) >= HEADER_GAP) {
                return false;
            }
            if (l.words().isEmpty() || shortDate(l.words().get(0).text()) != null || isDateHeader(l)) {
                return false;
            }
            return l.words().stream().anyMatch(w -> !isNum(w.text()));
        }

        /**
         * True when line {@code i} is part of the column header under the date header line {@code d}: same page,
         * close below it, and text only (no dates, no numbers). Months without a charge column leave a wider gap
         * between "Date" and "Amount (Rs)", so this is bounded by distance to the date line, not line to line.
         */
        private boolean headerBelow(int i, int d) {
            if (!samePage(d, i)) {
                return false;
            }
            Line l = lines.get(i);
            if (l.top() - lines.get(d).top() >= HEADER_DEPTH) {
                return false;
            }
            if (l.words().isEmpty() || shortDate(l.words().get(0).text()) != null || isDateHeader(l)) {
                return false;
            }
            return l.words().stream().noneMatch(w -> isNum(w.text()));
        }

        private Table wideTable(int hs, int he, int dateLine) {
            List<double[]> amts = new ArrayList<>(); // x0, x1
            List<double[]> units = new ArrayList<>();
            for (int i = hs; i <= he; i++) {
                List<Word> ws = lines.get(i).words();
                for (int w = 0; w < ws.size(); w++) {
                    Word x = ws.get(w);
                    if (x.text().equals("Amount")) {
                        double x1 = w + 1 < ws.size() && ws.get(w + 1).text().equals("(Rs)") ? ws.get(w + 1).x1() : x.x1();
                        amts.add(new double[] {x.x0(), x1});
                    } else if (x.text().equals("Units")) {
                        units.add(new double[] {x.x0(), x.x1()});
                    }
                }
            }
            amts.sort((a, b) -> Double.compare(a[0], b[0]));
            units.sort((a, b) -> Double.compare(a[0], b[0]));
            if (amts.isEmpty() || amts.size() != units.size()) {
                return null;
            }
            Table t = new Table();
            for (int g = 0; g < amts.size(); g++) {
                Group grp = new Group();
                grp.amtLo = g == 0 ? amts.get(0)[0] - 15 : t.groups.get(g - 1).hi;
                grp.unitsLo = (amts.get(g)[1] + units.get(g)[0]) / 2.0;
                grp.hi = g + 1 < amts.size() ? (units.get(g)[1] + amts.get(g + 1)[0]) / 2.0 : Double.POSITIVE_INFINITY;
                t.groups.add(grp);
            }
            List<StringBuilder> names = new ArrayList<>();
            for (int g = 0; g < t.groups.size(); g++) {
                names.add(new StringBuilder());
            }
            for (int i = hs; i <= he; i++) {
                for (Word w : lines.get(i).words()) {
                    if (TABLE_HEADER_WORDS.contains(w.text()) || w.center() < t.groups.get(0).amtLo) {
                        continue;
                    }
                    for (int g = 0; g < t.groups.size(); g++) {
                        if (w.center() >= t.groups.get(g).amtLo && w.center() < t.groups.get(g).hi) {
                            names.get(g).append(' ').append(w.text());
                            break;
                        }
                    }
                }
            }
            for (int g = 0; g < t.groups.size(); g++) {
                t.groups.get(g).name = collapse(names.get(g).toString());
            }
            return t;
        }

        private Table stackedTable(int hs, int dateLine) {
            Double amt0 = null;
            Double amt1 = null;
            Double nav0 = null;
            Double nav1 = null;
            Double units0 = null;
            for (Word w : lines.get(dateLine).words()) {
                switch (w.text()) {
                    case "Amount" -> {
                        amt0 = w.x0();
                        amt1 = w.x1();
                    }
                    case "NAV" -> {
                        nav0 = w.x0();
                        nav1 = w.x1();
                    }
                    case "Units" -> units0 = w.x0();
                    default -> { }
                }
            }
            if (amt0 == null || nav0 == null || units0 == null) {
                return null;
            }
            Table t = new Table();
            Group grp = new Group();
            grp.amtLo = amt0 - 20;
            grp.navLo = (amt1 + nav0) / 2.0;
            grp.unitsLo = (nav1 + units0) / 2.0;
            StringBuilder name = new StringBuilder();
            for (int i = hs; i < dateLine; i++) {
                for (Word w : lines.get(i).words()) {
                    name.append(' ').append(w.text());
                }
            }
            grp.name = collapse(name.toString());
            t.groups.add(grp);
            return t;
        }

        // -- shared --

        int indexOf(java.util.function.Predicate<Line> p, int from) {
            for (int i = Math.max(from, 0); i < lines.size(); i++) {
                if (p.test(lines.get(i))) {
                    return i;
                }
            }
            return -1;
        }

        boolean samePage(int a, int b) {
            return lines.get(a).words().get(0).page() == lines.get(b).words().get(0).page();
        }

        static List<Word> numbers(Line l) {
            List<Word> out = new ArrayList<>();
            for (Word w : l.words()) {
                if (isNum(w.text())) {
                    out.add(w);
                }
            }
            return out;
        }

        static int nearest(List<Word> anchors, double x) {
            int best = 0;
            double bd = Double.MAX_VALUE;
            for (int i = 0; i < anchors.size(); i++) {
                double d = Math.abs(anchors.get(i).center() - x);
                if (d < bd) {
                    bd = d;
                    best = i;
                }
            }
            return best;
        }

        static BigDecimal[] column(List<Word> row, List<Word> anchors, int scale) {
            BigDecimal[] out = new BigDecimal[anchors.size()];
            for (Word w : row) {
                out[nearest(anchors, w.center())] = num(w.text(), scale);
            }
            return out;
        }

        static LocalDate longDate(String mon, String day, String year) {
            int mi = MONTHS.indexOf(mon.toLowerCase(Locale.ROOT));
            if (mi < 0) {
                return null;
            }
            try {
                return LocalDate.of(Integer.parseInt(year), mi + 1, Integer.parseInt(day));
            } catch (DateTimeException e) {
                return null;
            }
        }
    }
}
