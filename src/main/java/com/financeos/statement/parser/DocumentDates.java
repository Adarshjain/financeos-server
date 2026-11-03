package com.financeos.statement.parser;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds a statement's own date or period in its first lines: every date notation Indian statements use, read
 * by its label ("Statement Date", "Billing Period", "Statement Period ... to ...", "from ... to ..."), whether
 * the value follows the label or sits in the row below a row of labels. Dates only; text never leaves the class.
 */
final class DocumentDates {

    private DocumentDates() {}

    /** The statement date (period end) and, when the document gives a range, its start. Dates stay in memory. */
    record Found(LocalDate end, LocalDate start, String how) {}

    private static final String MON = "(?:jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|june?|july?|aug(?:ust)?|sep(?:t(?:ember)?)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)";
    private static final Pattern DATE = Pattern.compile("(?i)(?<![\\d/.\\-])(?:"
            + "(?<dn>\\d{1,2})[/\\-.](?<dm>\\d{1,2})[/\\-.](?<dy>\\d{4}|\\d{2})"                                              // 15/09/2026 15-09-26 15.09.2026
            + "|(?<yy>\\d{4})[/\\-.](?<ym>\\d{1,2})[/\\-.](?<yd>\\d{1,2})"                                                  // 2026-09-15
            + "|(?<bd>\\d{1,2})(?:st|nd|rd|th)?[ \\-/.]{0,2}(?<bm>" + MON + ")(?![a-z])\\.?,?[ \\-/.]{0,2}'?(?<by>\\d{4}|\\d{2})"  // 15 Sep 2026, 15-Sep-26, 15Sep2026, 15 Sep '26
            + "|(?<mm>" + MON + ")(?![a-z])\\.?[ \\-/.]{0,2}(?<md>\\d{1,2})(?:st|nd|rd|th)?,?[ \\-/.]{0,2}(?<my>\\d{4})"          // Sep 15, 2026
            + ")(?![\\d])");

    private static final Pattern STMT_DATE = Pattern.compile("(?i)statement\\s+(?:generation\\s+)?(?:date|dt)\\b|date\\s+of\\s+statement|bill(?:ing)?\\s+date|stmt\\.?\\s+date");
    private static final Pattern PERIOD = Pattern.compile("(?i)(?:statement|billing|bill|stmt)\\s+(?:period|cycle)|period\\b|duration|statement\\s+for\\s+the\\s+period|(?<![a-z])from\\b");
    private static final Pattern DUE = Pattern.compile("(?i)(?:payment\\s+)?due\\s+date|pay\\s+by|payment\\s+due\\s+on|last\\s+date");
    private static final Pattern RANGE_SEP = Pattern.compile("(?i)\\s(?:to|till|through|until)\\s|\\s*[-\u2013\u2014]\\s*|\\s+and\\s+");

    private record Tok(int pos, int end, char type, LocalDate date) {}   // type: S statement date, P period, D due date, d date

    static List<LocalDate> datesIn(String line) {
        List<LocalDate> out = new ArrayList<>();
        for (Tok t : tokens(line)) if (t.type == 'd') out.add(t.date);
        return out;
    }

    private static List<Tok> tokens(String line) {
        List<Tok> out = new ArrayList<>();
        Matcher m = DATE.matcher(line);
        while (m.find()) {
            LocalDate d = toDate(m);
            if (d != null) out.add(new Tok(m.start(), m.end(), 'd', d));
        }
        // labels, ignoring those inside a date match
        addLabels(line, STMT_DATE, 'S', out);
        addLabels(line, PERIOD, 'P', out);
        addLabels(line, DUE, 'D', out);
        out.sort((a, b) -> a.pos != b.pos ? a.pos - b.pos : Character.compare(a.type, b.type));
        // the same label matched twice (period inside statement period): keep the longest at a position
        List<Tok> dedup = new ArrayList<>();
        for (Tok t : out) {
            if (t.type != 'd' && !dedup.isEmpty()) {
                Tok last = dedup.get(dedup.size() - 1);
                if (last.type != 'd' && t.pos < last.end) {
                    if (t.end - t.pos > last.end - last.pos) dedup.set(dedup.size() - 1, t);
                    continue;
                }
            }
            dedup.add(t);
        }
        return dedup;
    }

    private static void addLabels(String line, Pattern p, char type, List<Tok> out) {
        Matcher m = p.matcher(line);
        while (m.find()) out.add(new Tok(m.start(), m.end(), type, null));
    }

    private static LocalDate toDate(Matcher m) {
        try {
            int d, mo, y;
            if (m.group("dn") != null) {
                d = Integer.parseInt(m.group("dn"));
                mo = Integer.parseInt(m.group("dm"));
                y = year(m.group("dy"));
                if (mo > 12 && d <= 12) { int t = d; d = mo; mo = t; }   // an American m/d/y that cannot be d/m
            } else if (m.group("yy") != null) {
                y = Integer.parseInt(m.group("yy"));
                mo = Integer.parseInt(m.group("ym"));
                d = Integer.parseInt(m.group("yd"));
            } else if (m.group("bd") != null) {
                d = Integer.parseInt(m.group("bd"));
                mo = month(m.group("bm"));
                y = year(m.group("by"));
            } else {
                mo = month(m.group("mm"));
                d = Integer.parseInt(m.group("md"));
                y = Integer.parseInt(m.group("my"));
            }
            if (y < 2000 || y > 2100) return null;
            return LocalDate.of(y, mo, d);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static int year(String s) {
        int y = Integer.parseInt(s);
        return y < 100 ? 2000 + y : y;
    }

    private static int month(String s) {
        return switch (s.toLowerCase(Locale.ROOT).substring(0, 3)) {
            case "jan" -> 1; case "feb" -> 2; case "mar" -> 3; case "apr" -> 4; case "may" -> 5; case "jun" -> 6;
            case "jul" -> 7; case "aug" -> 8; case "sep" -> 9; case "oct" -> 10; case "nov" -> 11; default -> 12;
        };
    }

    /**
     * The statement date / period end from the first {@code limit} lines, or null. A labelled period range wins over a
     * labelled statement date; an unlabelled "date to date" range in the top lines is a last resort. A payment due
     * date is never taken as the statement date.
     */
    static Found find(List<String> lines, int limit) {
        int n = Math.min(limit, lines.size());
        LocalDate stmt = null, pEnd = null, pStart = null;
        String how = null;
        for (int i = 0; i < n; i++) {
            List<Tok> toks = tokens(lines.get(i));
            if (toks.stream().noneMatch(t -> t.type != 'd')) continue;
            if (isProse(lines.get(i), toks)) continue;
            // labels with the dates that follow them on the same line
            int labels = (int) toks.stream().filter(t -> t.type != 'd').count();
            boolean dateAfterLabel = false;
            for (int k = 0; k < toks.size(); k++) {
                Tok t = toks.get(k);
                if (t.type == 'd') continue;
                List<LocalDate> ds = new ArrayList<>();
                for (int j = k + 1; j < toks.size() && toks.get(j).type == 'd'; j++) {
                    if (!ds.isEmpty() && !rangeBetween(lines.get(i), toks.get(j - 1), toks.get(j)) && t.type != 'P') break;
                    ds.add(toks.get(j).date);
                    if (ds.size() == 2) break;
                }
                if (ds.isEmpty()) continue;
                dateAfterLabel = true;
                if (t.type == 'P' && ds.size() == 2) {
                    LocalDate a = ds.get(0), b = ds.get(1);
                    if (a.isAfter(b)) { LocalDate x = a; a = b; b = x; }
                    if (ChronoUnit.DAYS.between(a, b) <= 400 && pEnd == null) { pStart = a; pEnd = b; how = "range"; }
                } else if (t.type == 'S' && stmt == null) {
                    stmt = ds.get(0);
                } else if (t.type == 'P' && ds.size() == 1 && stmt == null && how == null) {
                    // "Period ending 15/09/2026" style: a single date after a period label
                    stmt = ds.get(0);
                }
            }
            if (dateAfterLabel && (pEnd != null || stmt != null) && labels == 1) {
                if (pEnd != null) break;
                continue;
            }
            // a row of labels with the values in the row(s) below
            long needed = toks.stream().filter(t -> t.type != 'd').count();
            if (toks.stream().anyMatch(t -> t.type == 'd')) continue;
            for (int j = i + 1; j <= Math.min(n - 1, i + 3); j++) {
                List<Tok> row = tokens(lines.get(j));
                List<LocalDate> ds = new ArrayList<>();
                for (Tok t : row) if (t.type == 'd') ds.add(t.date);
                if (ds.isEmpty()) continue;
                // sequential: PERIOD takes two dates (a range), the others one, in label order
                int need = 0;
                for (Tok t : toks) if (t.type != 'd') need += t.type == 'P' ? 2 : 1;
                if (ds.size() == need || (needed == 1 && ds.size() >= need)) {
                    int at = 0;
                    for (Tok t : toks) {
                        if (t.type == 'd') continue;
                        if (t.type == 'P') {
                            LocalDate a = ds.get(at), b = ds.get(at + 1);
                            at += 2;
                            if (a.isAfter(b)) { LocalDate x = a; a = b; b = x; }
                            if (ChronoUnit.DAYS.between(a, b) <= 400 && pEnd == null) { pStart = a; pEnd = b; how = "range"; }
                        } else {
                            LocalDate d = ds.get(at++);
                            if (t.type == 'S' && stmt == null) stmt = d;
                        }
                    }
                } else if (toks.stream().anyMatch(t -> t.type == 'P')) {
                    // mixed row: the first ascending pair of dates a plausible billing cycle apart is the period
                    for (int k = 0; k + 1 < ds.size() && pEnd == null; k++) {
                        long days = ChronoUnit.DAYS.between(ds.get(k), ds.get(k + 1));
                        if (days >= 5 && days <= 400) { pStart = ds.get(k); pEnd = ds.get(k + 1); how = "pair"; }
                    }
                }
                break;
            }
            if (pEnd != null) break;
        }
        if (pEnd != null) return new Found(pEnd, pStart, how);
        if (stmt != null) return new Found(stmt, null, "statement-date");
        // last resort: an unlabelled "date to date" range among the first lines (account statements print their period that way)
        for (int i = 0; i < Math.min(n, 14); i++) {
            String l = lines.get(i);
            List<Tok> toks = tokens(l);
            List<Tok> ds = toks.stream().filter(t -> t.type == 'd').toList();
            if (ds.size() == 2 && toks.size() == 2 && rangeBetween(l, ds.get(0), ds.get(1))) {
                LocalDate a = ds.get(0).date, b = ds.get(1).date;
                if (a.isAfter(b)) { LocalDate x = a; a = b; b = x; }
                if (ChronoUnit.DAYS.between(a, b) >= 5 && ChronoUnit.DAYS.between(a, b) <= 400) return new Found(b, a, "bare-range");
            }
        }
        return null;
    }

    private static boolean rangeBetween(String line, Tok a, Tok b) {
        String mid = line.substring(a.end, b.pos);
        return mid.length() <= 12 && RANGE_SEP.matcher(" " + mid + " ").find();
    }

    /** A sentence is not a label row: terms, offers and notes carry the same words with example dates. */
    private static boolean isProse(String line, List<Tok> toks) {
        String lower = line.toLowerCase(Locale.ROOT);
        int words = lower.split("\\s+").length;
        if (words > 22) return true;
        return lower.matches(".*\\b(?:will be|shall|is due|due on|has been|must|should|would|if you|please|within)\\b.*");
    }
}
