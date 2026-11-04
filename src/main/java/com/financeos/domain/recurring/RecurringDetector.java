package com.financeos.domain.recurring;

import com.financeos.domain.categorization.DescriptionNormalizer;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Pure detection rules: finds the latest run of 3+ payments at one steady interval and amount, and
 * classifies it. No persistence here.
 */
public final class RecurringDetector {

    public static final int MIN_PAYMENTS = 3;
    /** Every amount of a run lies within this share of the run's median. */
    static final BigDecimal AMOUNT_SPREAD = new BigDecimal("0.15");

    private static final Set<String> KNOWN_SERVICES = Set.of(
            "NETFLIX", "SPOTIFY", "HOTSTAR", "DISNEY", "PRIME VIDEO", "AMAZON PRIME", "YOUTUBE", "GOOGLE ONE",
            "GOOGLE STORAGE", "ICLOUD", "ITUNES", "APPLE", "MICROSOFT", "ADOBE", "DROPBOX", "NOTION", "OPENAI",
            "CHATGPT", "LINKEDIN", "SONYLIV", "SONY LIV", "ZEE5", "JIOCINEMA", "JIOSAAVN", "GAANA", "WYNK",
            "AUDIBLE", "KINDLE", "CANVA", "GITHUB", "SLACK", "ZOOM", "MEDIUM", "CRUNCHYROLL", "SWIGGY ONE",
            "ZOMATO GOLD", "TINDER", "BUMBLE", "HBO", "PARAMOUNT", "TIDAL", "DUOLINGO", "COURSERA", "UDEMY");

    private static final Pattern EMI_TEXT = Pattern.compile("\\b(EMI|LOANS?)\\b");
    private static final Pattern MANDATE = Pattern.compile("\\b(NACH|ACH|ECS|ENACH|MANDATE|AUTOPAY)\\b");
    private static final Pattern LENDER = Pattern.compile(
            "FINANCE|FINSERV|FINCORP|HOUSING|CAPITAL|LENDING|HOME LOANS|MICROFIN|CREDIT");

    private RecurringDetector() {
    }

    /** One debit as the detector sees it. */
    public record Payment(UUID transactionId, LocalDate date, BigDecimal amount, UUID accountId,
                          String description, String categoryKey) {
    }

    /** A detected run: {@code payments} oldest first, {@code amount} the run's median. */
    public record Series(RecurringPeriod period, RecurringKind kind, BigDecimal amount, List<Payment> payments) {
        public Payment latest() {
            return payments.get(payments.size() - 1);
        }

        public Payment earliest() {
            return payments.get(0);
        }
    }

    /** The merchant key of a description (the categoriser's normalised form); empty when nothing is left. */
    public static String merchantKey(String description) {
        return DescriptionNormalizer.normalize(description);
    }

    /** A display name from a merchant key: "NETFLIX COM" becomes "Netflix Com". */
    public static String displayName(String merchantKey) {
        StringBuilder sb = new StringBuilder();
        for (String word : merchantKey.toLowerCase(Locale.ROOT).split(" ")) {
            if (word.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return sb.length() > 100 ? sb.substring(0, 100) : sb.toString();
    }

    /**
     * The latest run of 3+ consecutive payments whose gaps all fit one period and whose amounts lie within 15%
     * of their median, provided the last payment is at most 1.5 periods old on {@code today}.
     * {@code payments} are all of one merchant, in any order.
     */
    public static Optional<Series> detect(List<Payment> payments, LocalDate today, String merchantKey) {
        if (payments.size() < MIN_PAYMENTS) {
            return Optional.empty();
        }
        List<Payment> sorted = new ArrayList<>(payments);
        sorted.sort(Comparator.comparing(Payment::date).thenComparing(Payment::amount));
        int last = sorted.size() - 1;
        long latestGap = ChronoUnit.DAYS.between(sorted.get(last - 1).date(), sorted.get(last).date());
        for (RecurringPeriod period : RecurringPeriod.values()) {
            if (!period.fits(latestGap)) {
                continue;
            }
            if (ChronoUnit.DAYS.between(sorted.get(last).date(), today) > period.maxStaleDays()) {
                return Optional.empty();
            }
            int from = last;
            while (from > 0 && period.fits(ChronoUnit.DAYS.between(sorted.get(from - 1).date(), sorted.get(from).date()))) {
                from--;
            }
            List<Payment> run = new ArrayList<>(sorted.subList(from, last + 1));
            while (run.size() >= MIN_PAYMENTS && !amountsSteady(run)) {
                run.remove(0);
            }
            if (run.size() < MIN_PAYMENTS) {
                return Optional.empty();
            }
            return Optional.of(new Series(period, classify(merchantKey, run), median(run), run));
        }
        return Optional.empty();
    }

    private static boolean amountsSteady(List<Payment> run) {
        BigDecimal median = median(run);
        BigDecimal tolerance = median.multiply(AMOUNT_SPREAD);
        for (Payment p : run) {
            if (p.amount().subtract(median).abs().compareTo(tolerance) > 0) {
                return false;
            }
        }
        return true;
    }

    static BigDecimal median(List<Payment> run) {
        List<BigDecimal> amounts = run.stream().map(Payment::amount).sorted().toList();
        int n = amounts.size();
        if (n % 2 == 1) {
            return amounts.get(n / 2);
        }
        return amounts.get(n / 2 - 1).add(amounts.get(n / 2)).divide(BigDecimal.valueOf(2), 4, RoundingMode.HALF_UP);
    }

    /** EMI for loan-instalment looks, SUBSCRIPTION for subscription-like categories or well-known services, else OTHER. */
    static RecurringKind classify(String merchantKey, List<Payment> run) {
        Payment latest = run.get(run.size() - 1);
        String category = latest.categoryKey() == null ? "" : latest.categoryKey().toLowerCase(Locale.ROOT);
        String raw = latest.description() == null ? "" : latest.description().toUpperCase(Locale.ROOT);
        if (category.equals("emi") || category.startsWith("emi.")) {
            return RecurringKind.EMI;
        }
        if (EMI_TEXT.matcher(raw).find() || (MANDATE.matcher(raw).find() && LENDER.matcher(raw).find())) {
            return RecurringKind.EMI;
        }
        if (category.equals("subscriptions") || category.startsWith("subscriptions.")
                || category.equals("entertainment") || category.startsWith("entertainment.")
                || category.equals("bills.tv")) {
            return RecurringKind.SUBSCRIPTION;
        }
        String padded = " " + merchantKey + " ";
        for (String service : KNOWN_SERVICES) {
            if (padded.contains(" " + service + " ")) {
                return RecurringKind.SUBSCRIPTION;
            }
        }
        return RecurringKind.OTHER;
    }
}
