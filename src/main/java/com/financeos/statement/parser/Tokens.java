package com.financeos.statement.parser;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class Tokens {
    private Tokens() {
    }

    public static final double Y_TOL = 3.0;
    public static final double SWEEP_TOL = 3.8;
    public static final double COL_TOL = 12.0;
    public static final double EPS = 0.011;

    static final Map<String, List<String>> HEADER_KEYWORDS;

    static {
        Map<String, List<String>> m = new LinkedHashMap<>();
        m.put("date", List.of("date", "txn date", "transaction date", "value date", "value dt", "post date"));
        m.put("description", List.of("description", "narration", "particulars", "details", "transaction details", "remarks"));
        m.put("debit", List.of("debit", "withdrawal", "withdrawals", "withdrawal amt", "debit amount", "dr", "paid out"));
        m.put("credit", List.of("credit", "deposit", "deposits", "deposit amt", "credit amount", "cr", "paid in"));
        m.put("balance", List.of("balance", "closing balance", "running balance", "available balance"));
        m.put("amount", List.of("amount", "amount (inr)", "transaction amount"));
        m.put("ref", List.of("ref", "ref no", "chq", "cheque", "chq/ref no", "cheque no", "reference"));
        HEADER_KEYWORDS = Collections.unmodifiableMap(m);
    }

    static final Pattern ACCOUNT_LABEL = Pattern.compile(
            "(?:account|a/c|a\\.c\\.|acct|card)[\\w\\-/]*\\s*(?:no|number|#)?\\.?\\s*[:.\\-]?\\s*"
                    + "([0-9Xx*](?:[0-9Xx*\\-]{4,22})[0-9Xx*])",
            Pattern.CASE_INSENSITIVE);
    static final Pattern IFSC = Pattern.compile("\\b([A-Z]{4}0[A-Z0-9]{6})\\b");
    static final Pattern BANK_BRAND = Pattern.compile(
            "\\b(bank|hsbc|citi|amex|american express|barclays)\\b", Pattern.CASE_INSENSITIVE);
    static final Pattern CARD_MASK = Pattern.compile(
            "\\b([0-9Xx*]{4}\\s+[0-9Xx*]{4}\\s+[0-9Xx*]{4}\\s+[0-9Xx*]{4}|[0-9Xx*]{14,19})\\b");
    static final Pattern NAME_LABEL = Pattern.compile(
            "(?:customer name|account holder|a/c holder|holder name|name)\\s*[:\\-]\\s*(.+)",
            Pattern.CASE_INSENSITIVE);
    static final Pattern PERIOD_LINE = Pattern.compile("(?:statement|period|from)", Pattern.CASE_INSENSITIVE);
    static final Pattern OPENING_BAL = Pattern.compile(
            "(?:opening balance|balance b/f|brought forward|b/f)", Pattern.CASE_INSENSITIVE);
    static final Pattern CLOSING_BAL = Pattern.compile(
            "(?:closing balance|balance c/f|carried forward)", Pattern.CASE_INSENSITIVE);

    static final Pattern DATE_TOKEN = Pattern.compile(
            "^(\\d{1,2}[/\\-.]\\d{1,2}[/\\-.]\\d{2,4}"
                    + "|\\d{1,2}[/\\-. ][A-Za-z]{3,9}[/\\-. ]\\d{2,4}"
                    + "|[A-Za-z]{3,9} \\d{1,2} \\d{2,4}"
                    + "|\\d{1,2}[A-Za-z]{3,9}\\d{2,4}"
                    + "|\\d{1,2}\\s?[A-Za-z]{3,9}"
                    + "|\\d{4}[/-]\\d{2}[/-]\\d{2})$");

    static final Pattern TIME = Pattern.compile(
            "^(\\d{1,2}:\\d{2}(:\\d{2})?(AM|PM|am|pm)?|AM|PM|am|pm|IST|Hrs|hrs)$");

    // Amount: needs decimal point or comma grouping so bare ref numbers don't match.
    // The currency prefix includes "C" and "`" - rupee glyphs extract as those in
    // HDFC and ICICI fonts respectively.
    static final Pattern AMOUNT = Pattern.compile(
            "^\\(?(?:[₹$€£C`]|Rs\\.?|INR)?\\s?(-?\\d{1,3}(?:,\\d{2,3})*\\.\\d{1,2}|-?\\d+\\.\\d{1,2})\\)?\\s*(Cr|CR|cr|Dr|DR|dr)?\\.?$");

    static final Pattern LOOSE_AMOUNT = Pattern.compile(
            "^(?:[₹$€£C`]|Rs\\.?|INR)?\\s?(\\d{1,3}(?:,\\d{2,3})+)$");

    static final Pattern SUMMARY_WORDS = Pattern.compile(
            "\\b(total|outstanding|balance|purchases|installments|summary|brought|carried)\\b",
            Pattern.CASE_INSENSITIVE);

    // Currency glyphs that extract as their own word ("C 1,787.61")
    private static final Set<String> CURRENCY_TOKENS = Set.of("C", "₹", "$", "€", "£", "Rs", "Rs.", "INR", "`");

    static String norm(String t) {
        String s = t.strip();
        int start = 0, end = s.length();
        while (start < end && s.charAt(start) == '|') {
            start++;
        }
        while (end > start && s.charAt(end - 1) == '|') {
            end--;
        }
        s = s.substring(start, end);
        end = s.length();
        while (end > 0 && ",;:".indexOf(s.charAt(end - 1)) >= 0) {
            end--;
        }
        return s.substring(0, end).strip();
    }

    // Bold headings in some PDFs render by double-printing every glyph and
    // extract as doubled letters ("DDUUEE DDAATTEE"). Collapse those.
    static String dedouble(String t) {
        int n = t.length();
        if (n >= 6 && n % 2 == 0) {
            boolean allMatch = true;
            for (int i = 0; i < n; i += 2) {
                if (t.charAt(i) != t.charAt(i + 1)) {
                    allMatch = false;
                    break;
                }
            }
            if (allMatch) {
                StringBuilder sb = new StringBuilder(n / 2);
                for (int i = 0; i < n; i += 2) {
                    sb.append(t.charAt(i));
                }
                return sb.toString();
            }
        }
        return t;
    }

    static Amount parseAmount(String token) {
        String t = token.strip();
        boolean trailingMinus = t.endsWith("-") && t.length() > 1;
        if (trailingMinus) {
            t = t.substring(0, t.length() - 1).strip();
        }
        Matcher m = AMOUNT.matcher(t);
        if (!m.matches()) {
            return null;
        }
        double value = Double.parseDouble(m.group(1).replace(",", ""));
        int sign = 0;
        if (m.group(2) != null) {
            sign = m.group(2).equalsIgnoreCase("cr") ? 1 : -1;
        }
        if (t.startsWith("(") || value < 0 || trailingMinus) {
            value = Math.abs(value);
            sign = -1;
        }
        return new Amount(value, sign);
    }

    static Double looseAmount(String token) {
        Amount a = parseAmount(token);
        if (a != null) {
            return a.value();
        }
        String t = norm(token);
        Matcher m = LOOSE_AMOUNT.matcher(t);
        if (m.matches()) {
            return Double.parseDouble(m.group(1).replace(",", ""));
        }
        return null;
    }

    static boolean isSummaryLine(String lineText) {
        Matcher m = SUMMARY_WORDS.matcher(lineText);
        Set<String> found = new HashSet<>();
        while (m.find()) {
            found.add(m.group(1).toLowerCase(Locale.ROOT));
        }
        return found.size() >= 2;
    }

    static boolean isTimeToken(String t) {
        return TIME.matcher(t).matches();
    }

    static boolean isCurrencyToken(String t) {
        return CURRENCY_TOKENS.contains(t);
    }

    static boolean isDateToken(String cand) {
        return DATE_TOKEN.matcher(cand).matches();
    }
}
