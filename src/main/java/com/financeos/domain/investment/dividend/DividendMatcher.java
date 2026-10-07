package com.financeos.domain.investment.dividend;

import com.financeos.domain.instrument.Instrument;
import com.financeos.domain.transaction.Transaction;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Pure scoring of one bank credit against one dividend row. No I/O, so the rules are unit-testable
 * without Spring. {@link DividendReceiptService} runs the candidate query, feeds candidates through
 * {@link #score} and does the greedy one-credit-per-dividend assignment.
 *
 * <p>Amount tiers (tolerance = max(₹1, 0.5% of gross), RTAs round per-share × quantity differently):
 * <ol>
 *   <li>EXACT — received ≈ gross, or ≈ gross − recorded TDS.</li>
 *   <li>NET_OF_TDS — TDS not recorded and received ≈ gross × 0.9.</li>
 *   <li>FUZZY — received anywhere in [0.78·gross, gross] (20% no-PAN TDS … rounding), or ≈ gross × a
 *       split ratio (Yahoo adjusts historical per-share amounts for splits, so pre-split expectations
 *       are low by the ratio). Only accepted when the narration carries a dividend keyword AND names
 *       the company (name score ≥ {@value #NAME_SCORE_THRESHOLD}).</li>
 * </ol>
 */
public final class DividendMatcher {

    private DividendMatcher() {
    }

    public static final BigDecimal TDS_RATE = new BigDecimal("0.10");
    public static final BigDecimal MIN_TOLERANCE = new BigDecimal("1.00");
    public static final BigDecimal TOLERANCE_RATE = new BigDecimal("0.005");
    public static final BigDecimal BAND_LOW_FACTOR = new BigDecimal("0.78");
    public static final BigDecimal BAND_HIGH_FACTOR = new BigDecimal("10");
    public static final double NAME_SCORE_THRESHOLD = 0.5;
    public static final int[] SPLIT_RATIOS = {2, 3, 4, 5, 10};
    public static final BigDecimal SPLIT_RATIO_SLACK = new BigDecimal("0.01");

    static final int SCORE_EXACT = 100;
    static final int SCORE_NET_OF_TDS = 85;
    static final int SCORE_FUZZY_BAND = 50;
    static final int SCORE_FUZZY_SPLIT = 40;
    static final int BONUS_KEYWORD = 5;
    static final int BONUS_NAME = 5;
    static final int MAX_DATE_PENALTY = 15;
    static final int DAYS_PER_PENALTY_POINT = 4;

    /** Tokens of an instrument name that say nothing about which company it is. */
    static final Set<String> STOPWORDS = Set.of(
            "ltd", "limited", "the", "of", "and", "co", "company", "corp", "corporation", "inc", "india",
            "fund", "plan", "growth", "direct", "regular", "idcw", "dividend", "payout", "option",
            "reinvestment", "reinvest", "scheme", "mutual", "etf", "nifty", "index", "bse", "nse",
            "shares", "share", "eq", "equity");

    public record Scored(
            Transaction transaction,
            DividendMatchTier tier,
            int score,
            List<DividendMatchReason> reasons,
            /** gross − received when the gap looks like tax deducted at source; null otherwise. */
            BigDecimal impliedTds,
            /** received − (gross − recorded TDS); positive = more money arrived than expected. */
            BigDecimal variance) {
    }

    public static BigDecimal tolerance(BigDecimal gross) {
        BigDecimal relative = gross.multiply(TOLERANCE_RATE).setScale(2, RoundingMode.HALF_UP);
        return relative.max(MIN_TOLERANCE);
    }

    /** Lower bound of the candidate query band. */
    public static BigDecimal bandLow(BigDecimal gross) {
        BigDecimal low = gross.multiply(BAND_LOW_FACTOR).setScale(2, RoundingMode.HALF_UP).subtract(tolerance(gross));
        return low.max(BigDecimal.ZERO);
    }

    /** Upper bound of the candidate query band (wide enough to catch split-ratio multiples). */
    public static BigDecimal bandHigh(BigDecimal gross) {
        return gross.multiply(BAND_HIGH_FACTOR).setScale(2, RoundingMode.HALF_UP).add(tolerance(gross));
    }

    public static Optional<Scored> score(Dividend dividend, Transaction txn, LocalDate baseDate) {
        BigDecimal gross = dividend.getAmount();
        BigDecimal received = txn.getAmount();
        if (gross == null || gross.signum() <= 0 || received == null) {
            return Optional.empty();
        }
        BigDecimal tol = tolerance(gross);
        BigDecimal tds = dividend.getTds();
        BigDecimal expectedNet = tds != null ? gross.subtract(tds) : gross;

        String desc = effectiveDescription(txn);
        Instrument instrument = dividend.getHolding() != null ? dividend.getHolding().getInstrument() : null;
        boolean keyword = hasKeyword(desc, dividend.getType());
        boolean symbolHit = symbolMatches(instrument, desc);
        double name = symbolHit ? 1.0 : nameScore(instrument, desc);

        List<DividendMatchReason> reasons = new ArrayList<>();
        DividendMatchTier tier;
        int score;
        BigDecimal impliedTds = null;

        if (within(received, gross, tol)) {
            tier = DividendMatchTier.EXACT;
            score = SCORE_EXACT;
            reasons.add(DividendMatchReason.EXACT_GROSS);
        } else if (tds != null && within(received, gross.subtract(tds), tol)) {
            tier = DividendMatchTier.EXACT;
            score = SCORE_EXACT;
            reasons.add(DividendMatchReason.NET_OF_RECORDED_TDS);
        } else if (tds == null && within(received, gross.multiply(BigDecimal.ONE.subtract(TDS_RATE)), tol)) {
            tier = DividendMatchTier.NET_OF_TDS;
            score = SCORE_NET_OF_TDS;
            reasons.add(DividendMatchReason.NET_OF_10PCT_TDS);
            impliedTds = gross.subtract(received);
        } else {
            boolean narrationAgrees = keyword && name >= NAME_SCORE_THRESHOLD;
            if (!narrationAgrees) {
                return Optional.empty();
            }
            if (received.compareTo(gross.add(tol)) <= 0) {
                if (received.compareTo(bandLow(gross)) < 0) {
                    return Optional.empty();
                }
                tier = DividendMatchTier.FUZZY;
                score = SCORE_FUZZY_BAND;
                reasons.add(DividendMatchReason.AMOUNT_WITHIN_BAND);
                if (received.compareTo(gross) < 0) {
                    impliedTds = gross.subtract(received);
                }
            } else {
                if (splitRatio(received, gross) == null) {
                    return Optional.empty();
                }
                tier = DividendMatchTier.FUZZY;
                score = SCORE_FUZZY_SPLIT;
                reasons.add(DividendMatchReason.SPLIT_RATIO_SUSPECT);
            }
        }

        if (keyword) {
            score += BONUS_KEYWORD;
            reasons.add(DividendMatchReason.DIVIDEND_KEYWORD);
        }
        if (symbolHit) {
            score += BONUS_NAME;
            reasons.add(DividendMatchReason.SYMBOL_MATCH);
        } else if (name >= NAME_SCORE_THRESHOLD) {
            score += BONUS_NAME;
            reasons.add(DividendMatchReason.NAME_MATCH);
        }
        if (baseDate != null && txn.getDate() != null) {
            long days = Math.abs(ChronoUnit.DAYS.between(baseDate, txn.getDate()));
            score -= (int) Math.min(MAX_DATE_PENALTY, days / DAYS_PER_PENALTY_POINT);
        }

        BigDecimal variance = received.subtract(expectedNet);
        return Optional.of(new Scored(txn, tier, score, List.copyOf(reasons), impliedTds, variance));
    }

    /** The common split ratio {@code received / gross} sits within 1% of, or null. */
    public static Integer splitRatio(BigDecimal received, BigDecimal gross) {
        if (gross == null || gross.signum() <= 0 || received == null) {
            return null;
        }
        BigDecimal ratio = received.divide(gross, 6, RoundingMode.HALF_UP);
        for (int r : SPLIT_RATIOS) {
            BigDecimal target = BigDecimal.valueOf(r);
            BigDecimal slack = target.multiply(SPLIT_RATIO_SLACK);
            if (ratio.subtract(target).abs().compareTo(slack) <= 0) {
                return r;
            }
        }
        return null;
    }

    /** Dividend rows look for DIV… or IDCW tokens; interest rows for INT, INTT or INTEREST…. */
    public static boolean hasKeyword(String description, DividendType type) {
        Set<String> tokens = tokens(description);
        if (type == DividendType.interest) {
            return tokens.stream().anyMatch(t -> t.equals("int") || t.equals("intt") || t.startsWith("interest"));
        }
        return tokens.stream().anyMatch(t -> t.startsWith("div") || t.equals("idcw"));
    }

    /**
     * Share of the instrument's significant name tokens found in the narration (a narration token
     * starting with a 4+ letter name token counts, so "INFOSYSLTD" still hits "infosys").
     */
    public static double nameScore(Instrument instrument, String description) {
        if (instrument == null || instrument.getName() == null) {
            return 0.0;
        }
        Set<String> significant = tokens(instrument.getName()).stream()
                .filter(t -> !STOPWORDS.contains(t) && t.length() >= 2 && !t.chars().allMatch(Character::isDigit))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (significant.isEmpty()) {
            return 0.0;
        }
        Set<String> descTokens = tokens(description);
        long hits = significant.stream().filter(name -> descTokens.contains(name)
                || (name.length() >= 4 && descTokens.stream().anyMatch(d -> d.startsWith(name)))).count();
        return (double) hits / significant.size();
    }

    /** Ticker present as its own token (3+ chars, so "ITC"/"TCS" count but "L" would not). */
    public static boolean symbolMatches(Instrument instrument, String description) {
        if (instrument == null || instrument.getSymbol() == null) {
            return false;
        }
        String symbol = instrument.getSymbol().trim().toLowerCase();
        if (symbol.length() < 3 || symbol.chars().allMatch(Character::isDigit)) {
            return false;
        }
        return tokens(description).contains(symbol);
    }

    public static String effectiveDescription(Transaction t) {
        String a = t.getSourcedDescription();
        String b = t.getDescription();
        if (a == null) {
            return b == null ? "" : b;
        }
        return b == null ? a : a + " " + b;
    }

    static Set<String> tokens(String s) {
        if (s == null || s.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(s.toLowerCase().split("[^a-z0-9]+"))
                .filter(t -> !t.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static boolean within(BigDecimal actual, BigDecimal target, BigDecimal tol) {
        return actual.subtract(target).abs().compareTo(tol) <= 0;
    }
}
