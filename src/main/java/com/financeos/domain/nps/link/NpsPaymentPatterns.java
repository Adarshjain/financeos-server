package com.financeos.domain.nps.link;

import org.springframework.lang.Nullable;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.regex.Pattern;

/**
 * What an NPS payment looks like in a bank or card transaction, and how a payment fits a contribution entry.
 * eNPS deducts its own charge from the money it receives, so the payment is a little above the contribution.
 */
public final class NpsPaymentPatterns {

    /** The words an NPS payment carries (e-NPS, NPS Trust, Protean, NSDL CRA, PFRDA, NPS contribution). */
    private static final Pattern NPS = Pattern.compile(
            "NPS ?TRUST|\\bE-?NPS\\b|PROTEAN|NPSCRA|NSDL ?CRA|PFRDA|NPS ?CONTRIB|NATIONAL PENSION",
            Pattern.CASE_INSENSITIVE);
    /** Words that make a match something else, such as the Rs 1 "ENPS KYC VERIFICATION" penny-drop. */
    private static final Pattern NOT_A_CONTRIBUTION = Pattern.compile("KYC|VERIFICATION|PENNY", Pattern.CASE_INSENSITIVE);

    /** The payment may exceed the entry amount by at most max(this, {@link #FEE_PERCENT}% of the amount). */
    static final BigDecimal FEE_FLOOR = new BigDecimal("30");
    static final BigDecimal FEE_PERCENT = new BigDecimal("0.003");
    /** The payment is dated at most this many days before the entry's posted date, never after it. */
    public static final int WINDOW_DAYS = 7;

    private NpsPaymentPatterns() {
    }

    /** True when either text names NPS and neither carries a KYC / verification / penny word. */
    public static boolean looksLikeNps(@Nullable String description, @Nullable String sourcedDescription) {
        boolean hit = false;
        for (String text : new String[]{description, sourcedDescription}) {
            if (text == null || text.isBlank()) {
                continue;
            }
            if (NOT_A_CONTRIBUTION.matcher(text).find()) {
                return false;
            }
            hit |= NPS.matcher(text).find();
        }
        return hit;
    }

    /** The NPS word the text carries (upper-cased, e.g. PROTEAN), or null when it has none. */
    @Nullable
    public static String matchedWord(@Nullable String text) {
        if (text == null) {
            return null;
        }
        java.util.regex.Matcher m = NPS.matcher(text);
        return m.find() ? m.group().toUpperCase(java.util.Locale.ROOT) : null;
    }

    /** Payment at or above the contribution, by no more than max(Rs 30, 0.3% of it), dated up to 7 days before its posted date. */
    public static boolean fits(BigDecimal payment, LocalDate paymentDate, BigDecimal entryAmount, LocalDate postedDate) {
        if (payment == null || paymentDate == null || entryAmount == null || postedDate == null
                || entryAmount.signum() <= 0) {
            return false;
        }
        BigDecimal extra = payment.subtract(entryAmount);
        BigDecimal tolerance = entryAmount.multiply(FEE_PERCENT).setScale(2, RoundingMode.HALF_UP).max(FEE_FLOOR);
        return extra.signum() >= 0 && extra.compareTo(tolerance) <= 0
                && !paymentDate.isBefore(postedDate.minusDays(WINDOW_DAYS)) && !paymentDate.isAfter(postedDate);
    }
}
