package com.financeos.domain.transaction.match;

import com.financeos.domain.transaction.TransactionMatcher;

import java.math.BigDecimal;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The one pair test: can record {@code r} and transaction {@code t} be the same payment? Hard rules first ({@link #canPair});
 * {@link #cost} only ranks pairs that passed. Every matching path calls these, so they always agree.
 */
public final class PairRules {

    private PairRules() {
    }

    /** Hard rules with the default foreign window. Any false means: not the same payment. */
    public static boolean canPair(MatchRecord r, MatchRecord t, int w) {
        return canPair(r, t, w, MatchWindow.DEFAULT_FOREIGN_DAYS);
    }

    /**
     * Hard rules. Any false means: not the same payment. When both sides carry a rupee amount (a foreign row the
     * statement already filled vs an INR mail or a second import) the rupee rules apply. Otherwise a foreign record
     * against a rupee one (a statement line) pairs on the currency code in the rupee one's text, the merchant and
     * {@code foreignW}, plus the printed foreign amount when the line shows one next to the code; two foreign records
     * (a resend, a provider / issuer pair) on the same currency and original amount within {@code w}.
     */
    public static boolean canPair(MatchRecord r, MatchRecord t, int w, int foreignW) {
        if (r == null || t == null) return false;
        if (r.accountId() == null || !Objects.equals(r.accountId(), t.accountId())) return false;
        if (r.type() == null || r.type() != t.type()) return false;
        if (r.cardLast4() != null && t.cardLast4() != null && !r.cardLast4().equals(t.cardLast4())) return false;
        if (!r.refs().isEmpty() && !t.refs().isEmpty() && Collections.disjoint(r.refs(), t.refs())) return false;
        if (r.amount() != null && t.amount() != null) {
            return r.amount().compareTo(t.amount()) == 0 && days(r, t) <= w;
        }
        if (r.isForeign() && t.isForeign()) {
            if (!r.currency().trim().equalsIgnoreCase(t.currency().trim())) return false;
            if (r.originalAmount() == null || t.originalAmount() == null
                    || r.originalAmount().compareTo(t.originalAmount()) != 0) return false;
            return days(r, t) <= w;
        }
        if (r.isForeign() || t.isForeign()) {
            MatchRecord foreign = r.isForeign() ? r : t;
            MatchRecord rupee = r.isForeign() ? t : r;
            if (!hasCurrencyCode(rupee.text(), foreign.currency())) return false;
            if (!merchantMatches(foreign.text(), rupee.text())) return false;
            BigDecimal printed = printedForeignAmount(rupee.text(), foreign.currency());
            if (printed != null && (foreign.originalAmount() == null
                    || printed.subtract(foreign.originalAmount()).abs().compareTo(CENT) > 0)) return false;
            return days(r, t) <= foreignW;
        }
        if (r.amount() == null || t.amount() == null || r.amount().compareTo(t.amount()) != 0) return false;
        return days(r, t) <= w;
    }

    /** Soft score, lower is better: 0 for a shared reference, else 10 per day apart plus 5 when the merchant differs. */
    public static int cost(MatchRecord r, MatchRecord t) {
        if (!r.refs().isEmpty() && !t.refs().isEmpty() && !Collections.disjoint(r.refs(), t.refs())) return 0;
        long c = 10 * days(r, t);
        if (!merchantMatches(r.text(), t.text())) c += 5;
        return (int) c;
    }

    /** Same counterparty after stripping rails (UPI, POS, NEFT…), numbers, masked numbers and legal suffixes. */
    public static boolean merchantMatches(String a, String b) {
        String na = normalise(a);
        String nb = normalise(b);
        if (na.isEmpty() || nb.isEmpty()) return false;
        return TransactionMatcher.namesSameCounterparty(na, nb);
    }

    /** True when {@code text} holds {@code code} (e.g. USD) as a whole word, any case. */
    public static boolean hasCurrencyCode(String text, String code) {
        if (text == null || code == null || code.isBlank()) return false;
        String c = code.trim();
        for (String token : text.split("[^A-Za-z]+")) {
            if (token.equalsIgnoreCase(c)) return true;
        }
        return false;
    }

    private static final BigDecimal CENT = new BigDecimal("0.01");
    private static final String DECIMAL = "(\\d[\\d,]*\\.\\d+)";

    /**
     * The foreign amount a statement line prints right beside the currency code ("USD 20.00", "20.00 USD"); decimals
     * only, so "CA 136 USD" (a state and a code) is no amount. Null when the line shows none.
     */
    static BigDecimal printedForeignAmount(String text, String code) {
        if (text == null || code == null || code.isBlank()) return null;
        String c = Pattern.quote(code.trim());
        Matcher m = Pattern.compile("(?i)(?<![A-Za-z])" + c + "\\s*" + DECIMAL
                + "|(?<![\\d.,])" + DECIMAL + "\\s*" + c + "(?![A-Za-z])").matcher(text);
        if (!m.find()) return null;
        String n = m.group(1) != null ? m.group(1) : m.group(2);
        try {
            return new BigDecimal(n.replace(",", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static final Pattern RAIL_PREFIX = Pattern.compile("\\b(?:UPI_|UPI/|UPI-)", Pattern.CASE_INSENSITIVE);
    private static final Set<String> STOP_WORDS = Set.of("upi", "pos", "vpa", "neft", "imps", "rtgs",
            "ltd", "limited", "pvt", "private", "llp", "inc", "corp");
    /** Masked numbers: XX1234, XXXX XXXX 1234, 4XXXXXXXXXXX1234, **1234. */
    private static final Pattern MASKED = Pattern.compile("[0-9]*[x*]{2,}[0-9x*]*", Pattern.CASE_INSENSITIVE);

    static String normalise(String s) {
        if (s == null) return "";
        String t = RAIL_PREFIX.matcher(s).replaceAll(" ");
        StringBuilder out = new StringBuilder();
        for (String token : t.toLowerCase(Locale.ROOT).split("[^a-z0-9*]+")) {
            if (token.isEmpty() || STOP_WORDS.contains(token)) continue;
            if (token.chars().allMatch(Character::isDigit)) continue;
            if (MASKED.matcher(token).matches()) continue;
            if (out.length() > 0) out.append(' ');
            out.append(token.replace("*", ""));
        }
        return out.toString().trim();
    }

    private static long days(MatchRecord r, MatchRecord t) {
        if (r.date() == null || t.date() == null) return Long.MAX_VALUE / 100;
        return Math.abs(ChronoUnit.DAYS.between(r.date(), t.date()));
    }
}
