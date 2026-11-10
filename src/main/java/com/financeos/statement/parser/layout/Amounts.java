package com.financeos.statement.parser.layout;

import java.math.BigDecimal;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The amount grammar of a printed figure, kept in BigDecimal: decimal point required, optional currency, Cr/Dr, minus. */
final class Amounts {

    private Amounts() {
    }

    /** value is never negative; negativeMarked = printed with a minus or in brackets; suffix = glued "Cr"/"Dr" or null. */
    record Parsed(BigDecimal value, boolean negativeMarked, String suffix) {
    }

    private static final Pattern AMOUNT = Pattern.compile(
            "^\\(?(?:(-)?(?:[₹$€£C`]|Rs\\.?|INR))?\\s?(-?\\d{1,3}(?:,\\d{2,3})*\\.\\d{1,2}|-?\\d+\\.\\d{1,2})\\)?\\s*(Cr|CR|cr|Dr|DR|dr)?\\.?$");

    static String norm(String t) {
        String s = t.strip();
        int start = 0;
        int end = s.length();
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

    static Parsed parse(String token) {
        String t = norm(token);
        boolean trailingMinus = t.endsWith("-") && t.length() > 1;
        if (trailingMinus) {
            t = t.substring(0, t.length() - 1).strip();
        }
        Matcher m = AMOUNT.matcher(t);
        if (!m.matches()) {
            return null;
        }
        BigDecimal v = new BigDecimal(m.group(2).replace(",", ""));
        boolean neg = t.startsWith("(") || v.signum() < 0 || trailingMinus || m.group(1) != null;
        return new Parsed(v.abs(), neg, m.group(3));
    }

    static boolean isAmount(String token) {
        return parse(token) != null;
    }
}
