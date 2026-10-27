package com.financeos.domain.epf.passbook;

import com.financeos.domain.epf.passbook.EpfPassbookFormatException.Reason;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

/** Pure parser for the EPFO member passbook PDF (text layer). No I/O besides the given bytes. */
@Component
public class EpfPassbookParser {

    static final String NOT_A_PASSBOOK_MSG =
            "This file is not an EPFO passbook. Download the passbook PDF from the EPFO site and try again.";

    static final String MEMBER_ID = "[A-Z]{5}\\d{17}|[A-Z]{2}/[A-Z]{3}/\\d+/\\d+/\\d+";
    static final String EST_ID = "[A-Z]{5}\\d{10}|[A-Z]{2}/[A-Z]{3}/\\d+";

    static final Pattern UAN = Pattern.compile("(?i)\\bUAN\\b\\s*(?:no\\.?|number)?\\s*[:\\-]?\\s*(\\d{12})\\b");
    static final Pattern MEMBER_LABEL = Pattern.compile(
            "(?i)member\\s*id(?:\\s*/\\s*name)?\\s*[:\\-]?\\s*(" + MEMBER_ID + ")");
    static final Pattern PF_ACCOUNT = Pattern.compile(
            "(?i)PF\\s*ACCOUNT\\s*(?:NUMBER|NO\\.?)?\\s*[:\\-]?\\s*(" + MEMBER_ID + ")");
    static final Pattern MEMBER_ANY = Pattern.compile("(?<![A-Za-z0-9/])(" + MEMBER_ID + ")(?![A-Za-z0-9])");
    static final Pattern EST_LABEL = Pattern.compile(
            "(?i)establishment\\s*id(?:\\s*/\\s*name)?\\s*[:\\-]?\\s*(" + EST_ID + ")(?:\\s*/\\s*(.+))?$");
    static final Pattern EST_NAME_LABEL = Pattern.compile("(?i)establishment\\s*name\\s*[:\\-]\\s*(.+)$");

    static final Pattern AMOUNT = Pattern.compile("\\d{1,3}(?:,\\d{2,3})*(?:\\.\\d{1,2})?|\\d{1,10}(?:\\.\\d{1,2})?");
    static final Pattern DATE = Pattern.compile("(?<!\\d)(\\d{1,2})[-/](\\d{1,2})[-/](\\d{4})(?!\\d)");
    static final Pattern HEAD = Pattern.compile(
            "^(?:(?i:(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec))[-\\s](\\d{4})\\s+)?"
                    + "(?:(\\d{1,2}[-/]\\d{1,2}[-/]\\d{4})\\s+)?"
                    + "(?:(?i:(CR|DR))\\b\\s*)?(.*)$");
    static final Pattern UPTO = Pattern.compile("(?i)upto\\s+(\\d{1,2}[-/]\\d{1,2}[-/]\\d{4})");
    static final Pattern FY_HEADER = Pattern.compile("(?i)financial\\s*year\\s*[:\\-\u2013]?\\s*(\\d{4})\\s*[-\u2013/]\\s*\\d{2,4}");
    static final Pattern DUE_MONTH = Pattern.compile("(?i)due[-\\s]*month\\s*[:\\-]?\\s*(\\d{2})(\\d{4})");
    static final Pattern NON_ROW_LABEL = Pattern.compile(
            "(?i)^(?:page\\b|total\\b|grand\\s+total|closing\\b|note\\b|disclaimer|uan\\b|member\\b|establishment\\b|"
                    + "pf\\s+account|name\\b|wage\\s+month|date\\s+of\\b|father|husband|spouse|employee|birth|dob|gender|address|mobile|email).*");

    private static final String[] MONTHS = {"jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec"};

    private static final class Raw {
        String type; // CR / DR / null
        YearMonth wageMonth;
        LocalDate date;
        String particulars;
        /** The row's own particulars, before any wrapped lines: what its type is read from. */
        String lead;
        List<BigDecimal> amounts;
        int continuations;
    }

    public ParsedPassbook parse(byte[] pdf) {
        String text = extractText(pdf);
        String[] lines = text.split("\\r?\\n");
        Matcher fyHeader = FY_HEADER.matcher(text);
        LocalDate fyStart = fyHeader.find() ? LocalDate.of(Integer.parseInt(fyHeader.group(1)), 4, 1) : null;

        String uan = null, memberId = null, estId = null, estName = null;
        List<Raw> raws = new ArrayList<>();
        int unrecognised = 0;
        ClosingBalance closing = null;
        Raw last = null;
        // A row whose particulars wrapped: its due month and amounts come on the next line.
        Raw pending = null;

        for (String rawLine : lines) {
            String line = rawLine.replace(' ', ' ').trim().replaceAll("\\s+", " ");
            if (line.isEmpty()) {
                last = null;
                continue;
            }
            Matcher m;
            if (uan == null && (m = UAN.matcher(line)).find()) uan = m.group(1);
            if (memberId == null) {
                if ((m = MEMBER_LABEL.matcher(line)).find() || (m = PF_ACCOUNT.matcher(line)).find()) {
                    memberId = m.group(1).toUpperCase(Locale.ROOT);
                }
            }
            if (estId == null && (m = EST_LABEL.matcher(line)).find()) {
                estId = m.group(1).toUpperCase(Locale.ROOT);
                if (m.group(2) != null && estName == null) estName = m.group(2).trim();
            }
            if (estName == null && (m = EST_NAME_LABEL.matcher(line)).find()) estName = m.group(1).trim();

            String lower = line.toLowerCase(Locale.ROOT);
            if (pending != null) {
                Raw p = pending;
                pending = null;
                List<BigDecimal> tail = new ArrayList<>();
                String rest = stripAmounts(line, tail);
                if (tail.size() >= 2 && rest.matches("\\d{0,6}")) {
                    if (!rest.isEmpty()) p.particulars = p.particulars + " " + rest;
                    p.lead = p.particulars;
                    p.amounts = tail;
                    raws.add(p);
                    last = p;
                    continue;
                }
                unrecognised++;
            }
            if (lower.startsWith("total ") || lower.startsWith("grand total")) {
                last = null;
                continue;
            }
            if (lower.contains("closing balance")) {
                List<BigDecimal> amounts = new ArrayList<>();
                String head = stripAmounts(line, amounts);
                if (amounts.size() >= 2) {
                    BigDecimal[] a = map(amounts);
                    closing = new ClosingBalance(a[0], a[1], firstDate(head));
                }
                last = null;
                continue;
            }

            List<BigDecimal> amounts = new ArrayList<>();
            String head = stripAmounts(line, amounts);
            Matcher h = HEAD.matcher(head);
            h.matches();
            YearMonth wm = h.group(1) != null ? yearMonth(h.group(1), h.group(2)) : null;
            LocalDate date = h.group(3) != null ? parseDate(h.group(3)) : null;
            String type = h.group(4) != null ? h.group(4).toUpperCase(Locale.ROOT) : null;
            String part = h.group(5).trim();
            String pl = part.toLowerCase(Locale.ROOT);
            boolean obOrInt = pl.matches("^(ob\\b|opening balance|int\\b|interest).*");
            // A year's totals can carry a date, wage month or CR/DR before "Total": a summary, never an entry.
            if (isSummary(pl)) {
                last = null;
                continue;
            }

            if (amounts.size() >= 2 && (date != null || type != null || obOrInt)) {
                Raw r = new Raw();
                r.type = type;
                r.wageMonth = wm;
                r.date = date;
                r.particulars = part;
                r.lead = part;
                r.amounts = amounts;
                raws.add(r);
                last = r;
                continue;
            }
            if (amounts.isEmpty() && date == null && type == null && wm == null
                    && last != null && last.continuations < 2
                    && line.length() <= 80 && line.indexOf(':') < 0 && !NON_ROW_LABEL.matcher(line).matches()
                    && !isTableHeader(lower)) {
                last.particulars = last.particulars + " " + line;
                last.continuations++;
                continue;
            }
            if (date != null && type != null) {
                if (amounts.isEmpty()) {
                    Raw r = new Raw();
                    r.type = type;
                    r.wageMonth = wm;
                    r.date = date;
                    r.particulars = part;
                    pending = r;
                    last = null;
                    continue;
                }
                unrecognised++;
            }
            last = null;
        }

        if (pending != null) unrecognised++;

        if (memberId == null) {
            Matcher m = MEMBER_ANY.matcher(text);
            if (m.find()) memberId = m.group(1);
        }
        if (uan == null && memberId == null) throw notPassbook();

        List<ParsedPassbookRow> rows = new ArrayList<>();
        for (Raw r : raws) {
            ParsedPassbookRow row = classify(rows.size(), r, fyStart);
            if (row == null) unrecognised++;
            // EPFO prints "Int. Updated upto 31/03/…" at 0 before the year's interest is credited: not an entry.
            else if (!(row.type() == PassbookRowType.INTEREST && row.employee().signum() == 0
                    && row.employer().signum() == 0)) rows.add(row);
        }
        if (rows.isEmpty()) throw notPassbook();
        return new ParsedPassbook(uan, memberId, estId, estName, List.copyOf(rows), unrecognised, closing);
    }

    /** The passbook table's column header ("Transaction / Wages / Contribution …"), sometimes printed under the OB line. */
    static boolean isTableHeader(String lower) {
        return lower.contains("wages") && (lower.contains("transaction") || lower.contains("contribution") || lower.contains("particulars"));
    }

    /** "Total Contributions for the year [2025]", "Grand Total …": the passbook's own sums. */
    static boolean isSummary(String particulars) {
        String p = particulars.replaceFirst("^[\\[\\]\\s\\d-]+", "");
        return p.startsWith("total") || p.startsWith("grand total") || particulars.contains("for the year");
    }

    private ParsedPassbookRow classify(int index, Raw r, LocalDate fyStart) {
        String p = r.particulars;
        String pl = r.lead.toLowerCase(Locale.ROOT);
        BigDecimal[] a = map(r.amounts);
        LocalDate upto = null;
        Matcher um = UPTO.matcher(p);
        if (um.find()) upto = parseDate(um.group(1));

        PassbookRowType type;
        PassbookWithdrawalKind kind = null;
        if (pl.matches("^ob\\b.*") || pl.contains("opening balance")) {
            type = PassbookRowType.OPENING_BALANCE;
        } else if (pl.startsWith("cont") || pl.contains("contribution")) {
            type = PassbookRowType.CONTRIBUTION;
        } else if (pl.contains("transfer") || pl.matches(".*\\btrf\\b.*") || pl.matches("^trans.*")) {
            if ("CR".equals(r.type)) type = PassbookRowType.TRANSFER_IN;
            else if ("DR".equals(r.type)) type = PassbookRowType.TRANSFER_OUT;
            else return null;
        } else if (pl.matches("^int\\b.*") || pl.startsWith("interest") || pl.contains("interest")) {
            type = PassbookRowType.INTEREST;
        } else if ("DR".equals(r.type)) {
            type = PassbookRowType.WITHDRAWAL;
            // The kind of claim can be on a wrapped line, so it is read from the whole particulars.
            pl = p.toLowerCase(Locale.ROOT);
            if (pl.contains("final") || pl.contains("settlement") || pl.contains("69(1)") || pl.contains("para 69")) {
                kind = PassbookWithdrawalKind.FINAL_SETTLEMENT;
            } else if (pl.contains("10c") || pl.contains("10 c") || pl.contains("pension")) {
                kind = PassbookWithdrawalKind.PENSION;
            } else {
                kind = PassbookWithdrawalKind.PARTIAL;
            }
        } else {
            return null;
        }

        YearMonth wageMonth = null;
        Integer fy = null;
        LocalDate credited = r.date;
        switch (type) {
            case CONTRIBUTION -> {
                wageMonth = r.wageMonth;
                if (wageMonth == null) {
                    Matcher dm = DUE_MONTH.matcher(p);
                    if (dm.find()) {
                        int mo = Integer.parseInt(dm.group(1));
                        if (mo >= 1 && mo <= 12) wageMonth = YearMonth.of(Integer.parseInt(dm.group(2)), mo).minusMonths(1);
                    }
                }
            }
            case OPENING_BALANCE -> {
                // The passbook's own financial year start wins: "OB Int. Updated upto 01/04/2024" would give 2 April.
                if (fyStart != null) credited = fyStart;
                else if (upto != null) credited = upto.plusDays(1);
            }
            case INTEREST -> {
                LocalDate ref = upto != null ? upto : r.date;
                if (credited == null) credited = upto;
                if (ref != null) fy = ref.getMonthValue() <= 3 ? ref.getYear() - 1 : ref.getYear();
            }
            default -> { }
        }
        if (credited == null) credited = firstDate(p);
        if (credited == null) return null;
        return new ParsedPassbookRow(index, type, kind, wageMonth, credited, fy, a[0], a[1], a[2], p);
    }

    /** Maps trailing amounts: 5 -> wages,eps,ee,er,pen; 4 -> last 3; 3 -> ee,er,pen; 2 -> ee,er. */
    private static BigDecimal[] map(List<BigDecimal> n) {
        int s = n.size();
        if (s >= 5) return new BigDecimal[] {n.get(s - 3), n.get(s - 2), n.get(s - 1)};
        if (s == 4) return new BigDecimal[] {n.get(1), n.get(2), n.get(3)};
        if (s == 3) return new BigDecimal[] {n.get(0), n.get(1), n.get(2)};
        return new BigDecimal[] {n.get(0), n.get(1), BigDecimal.ZERO};
    }

    /** Removes up to 5 trailing amount tokens (stopping before a due-month token) and returns the head. */
    private static String stripAmounts(String line, List<BigDecimal> out) {
        String[] t = line.split(" ");
        int end = t.length;
        List<BigDecimal> rev = new ArrayList<>();
        while (end > 0 && rev.size() < 5 && AMOUNT.matcher(t[end - 1]).matches()) {
            if (end >= 2 && t[end - 2].toLowerCase(Locale.ROOT).matches(".*month:?")) break;
            rev.add(new BigDecimal(t[end - 1].replace(",", "")));
            end--;
        }
        for (int i = rev.size() - 1; i >= 0; i--) out.add(rev.get(i));
        return String.join(" ", java.util.Arrays.copyOf(t, end));
    }

    private static LocalDate firstDate(String s) {
        Matcher m = DATE.matcher(s);
        return m.find() ? parseDate(m.group(0)) : null;
    }

    private static LocalDate parseDate(String s) {
        Matcher m = DATE.matcher(s);
        if (!m.find()) return null;
        try {
            return LocalDate.of(Integer.parseInt(m.group(3)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(1)));
        } catch (DateTimeException e) {
            return null;
        }
    }

    private static YearMonth yearMonth(String mon, String year) {
        String k = mon.toLowerCase(Locale.ROOT);
        for (int i = 0; i < MONTHS.length; i++) {
            if (MONTHS[i].equals(k)) return YearMonth.of(Integer.parseInt(year), i + 1);
        }
        return null;
    }

    private static EpfPassbookFormatException notPassbook() {
        return new EpfPassbookFormatException(Reason.NOT_A_PASSBOOK, NOT_A_PASSBOOK_MSG);
    }

    private static String extractText(byte[] pdf) {
        if (pdf == null || pdf.length == 0) {
            throw new EpfPassbookFormatException(Reason.UNREADABLE, "The file could not be read as a PDF.");
        }
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            return stripper.getText(doc);
        } catch (InvalidPasswordException e) {
            throw new EpfPassbookFormatException(Reason.PASSWORD_PROTECTED,
                    "This PDF is password protected. Remove the password and try again.");
        } catch (IOException | RuntimeException e) {
            throw new EpfPassbookFormatException(Reason.UNREADABLE, "The file could not be read as a PDF.");
        }
    }
}
