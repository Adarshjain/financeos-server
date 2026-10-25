package com.financeos.domain.investment;

import com.financeos.domain.instrument.corporateaction.CorporateAction;
import com.financeos.domain.instrument.corporateaction.CorporateActionType;
import org.springframework.lang.Nullable;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * A holding's move since the previous price update: the latest {@code instrument_prices} row
 * against the one before it (mutual-fund NAVs land a day late, so "previous" is the previous
 * stored price, not yesterday's date).
 *
 * <pre>
 *   dayChange    = (lastPrice − previousClose) × openQty            (scale 2)
 *   dayChangePct = (lastPrice − previousClose) ÷ previousClose × 100 (scale 2)
 * </pre>
 * Only for an open position priced off the latest stored row; otherwise every field is null.
 *
 * <p>{@link #forPosition} (what positions, the summary and top movers use) adds two gates and a
 * corporate-action adjustment on top of {@link #of}:
 * <ul>
 *   <li>Current prices only: the latest row must be at most {@value #CURRENT_WITHIN_DAYS} days
 *       before today and the previous row at most {@value #PREVIOUS_WITHIN_DAYS} days before the
 *       latest; a stale or gappy series has no day change.</li>
 *   <li>A split or bonus with its ex-date in {@code (previousAsOf, lastAsOf]} rescales the previous
 *       close by {@code ratioFrom ÷ ratioTo} (the quantity is already post-action), so a 1:2 split is
 *       not a fake −50%; {@code previousClose} is reported adjusted. Any other corporate action in
 *       that window (merger, demerger, a split/bonus without a usable ratio) makes the move ambiguous:
 *       no day change.</li>
 * </ul>
 */
public record DayChange(@Nullable BigDecimal previousClose, @Nullable LocalDate previousCloseAsOf,
                        @Nullable BigDecimal dayChange, @Nullable BigDecimal dayChangePct,
                        @Nullable Instant previousCloseAt) {

    public static final DayChange NONE = new DayChange(null, null, null, null);

    public DayChange(@Nullable BigDecimal previousClose, @Nullable LocalDate previousCloseAsOf,
                     @Nullable BigDecimal dayChange, @Nullable BigDecimal dayChangePct) {
        this(previousClose, previousCloseAsOf, dayChange, dayChangePct, null);
    }

    /** The latest stored price counts as current when it is at most this many days before today. */
    public static final int CURRENT_WITHIN_DAYS = 4;
    /** The previous stored price must be at most this many days before the latest one. */
    public static final int PREVIOUS_WITHIN_DAYS = 7;

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    /**
     * One stored close. {@code feedPreviousClose} is the prior session's close a Yahoo feed row carries
     * (instrument_prices.previous_close); null on AMFI and MANUAL rows and on older rows.
     */
    public record PricePoint(LocalDate asOf, BigDecimal close, @Nullable Instant at,
                             @Nullable BigDecimal feedPreviousClose) {
        public PricePoint(LocalDate asOf, BigDecimal close, @Nullable Instant at) {
            this(asOf, close, at, null);
        }

        public PricePoint(LocalDate asOf, BigDecimal close) {
            this(asOf, close, null, null);
        }
    }

    /**
     * The move against the feed's own previous close, when the latest row (the one priced) is a Yahoo
     * feed row carrying one. The previous row, when there is one, only supplies previousCloseAsOf/At.
     */
    @Nullable
    private static DayChange ofFeedPreviousClose(@Nullable BigDecimal openQty, @Nullable BigDecimal lastPrice,
                                                 @Nullable LocalDate lastPriceAsOf, @Nullable List<PricePoint> latestFirst) {
        if (openQty == null || openQty.signum() <= 0 || lastPrice == null || lastPriceAsOf == null
                || latestFirst == null || latestFirst.isEmpty()
                || !lastPriceAsOf.equals(latestFirst.get(0).asOf())
                || latestFirst.get(0).feedPreviousClose() == null) {
            return null;
        }
        BigDecimal previousClose = latestFirst.get(0).feedPreviousClose();
        BigDecimal move = lastPrice.subtract(previousClose);
        BigDecimal change = move.multiply(openQty).setScale(2, RoundingMode.HALF_UP);
        BigDecimal pct = previousClose.signum() == 0
                ? null
                : move.multiply(HUNDRED).divide(previousClose, 2, RoundingMode.HALF_UP);
        PricePoint prior = latestFirst.size() > 1 ? latestFirst.get(1) : null;
        return new DayChange(previousClose, prior == null ? null : prior.asOf(), change, pct,
                prior == null ? null : prior.at());
    }

    /**
     * The raw move between the latest two stored closes (no staleness gate, no corporate-action
     * adjustment).
     *
     * @param latestFirst the instrument's latest two stored closes, newest first (may be shorter)
     */
    public static DayChange of(@Nullable BigDecimal openQty, @Nullable BigDecimal lastPrice,
                               @Nullable LocalDate lastPriceAsOf, @Nullable List<PricePoint> latestFirst) {
        if (openQty == null || openQty.signum() <= 0 || lastPrice == null || lastPriceAsOf == null
                || latestFirst == null || latestFirst.size() < 2
                || !lastPriceAsOf.equals(latestFirst.get(0).asOf())) {
            return NONE;
        }
        PricePoint previous = latestFirst.get(1);
        BigDecimal move = lastPrice.subtract(previous.close());
        BigDecimal change = move.multiply(openQty).setScale(2, RoundingMode.HALF_UP);
        BigDecimal pct = previous.close().signum() == 0
                ? null
                : move.multiply(HUNDRED).divide(previous.close(), 2, RoundingMode.HALF_UP);
        return new DayChange(previous.close(), previous.asOf(), change, pct, previous.at());
    }

    /**
     * The day change a position shows: {@link #of} on a current price series, with the previous
     * close adjusted for a split/bonus between the two closes (see the class doc).
     *
     * @param actions the instrument's corporate actions (any dates; only those with an ex-date in
     *                {@code (previousAsOf, lastAsOf]} matter); null or empty for none
     */
    public static DayChange forPosition(@Nullable BigDecimal openQty, @Nullable BigDecimal lastPrice,
                                        @Nullable LocalDate lastPriceAsOf, @Nullable List<PricePoint> latestFirst,
                                        LocalDate today, @Nullable List<CorporateAction> actions) {
        if (lastPriceAsOf != null && latestFirst != null && !latestFirst.isEmpty()
                && latestFirst.get(0).feedPreviousClose() != null
                && lastPriceAsOf.equals(latestFirst.get(0).asOf())
                && !hasActionInWindow(actions, latestFirst.size() > 1 ? latestFirst.get(1).asOf()
                        : lastPriceAsOf.minusDays(PREVIOUS_WITHIN_DAYS), lastPriceAsOf)) {
            if (lastPriceAsOf.isBefore(today.minusDays(CURRENT_WITHIN_DAYS))) {
                return NONE;
            }
            DayChange feed = ofFeedPreviousClose(openQty, lastPrice, lastPriceAsOf, latestFirst);
            return feed != null ? feed : NONE;
        }
        if (lastPriceAsOf == null || latestFirst == null || latestFirst.size() < 2) {
            return NONE;
        }
        LocalDate previousAsOf = latestFirst.get(1).asOf();
        if (lastPriceAsOf.isBefore(today.minusDays(CURRENT_WITHIN_DAYS))
                || previousAsOf.isBefore(lastPriceAsOf.minusDays(PREVIOUS_WITHIN_DAYS))) {
            return NONE;
        }
        BigDecimal factor = BigDecimal.ONE;
        if (actions != null) {
            for (CorporateAction ca : actions) {
                LocalDate ex = ca.getExDate();
                if (ex == null || !ex.isAfter(previousAsOf) || ex.isAfter(lastPriceAsOf)) {
                    continue;
                }
                boolean rescales = (ca.getType() == CorporateActionType.split || ca.getType() == CorporateActionType.bonus)
                        && ca.getRatioFrom() != null && ca.getRatioFrom() > 0
                        && ca.getRatioTo() != null && ca.getRatioTo() > 0;
                if (!rescales) {
                    return NONE;
                }
                factor = factor.multiply(BigDecimal.valueOf(ca.getRatioFrom()))
                        .divide(BigDecimal.valueOf(ca.getRatioTo()), 10, RoundingMode.HALF_UP);
            }
        }
        if (factor.compareTo(BigDecimal.ONE) == 0) {
            return of(openQty, lastPrice, lastPriceAsOf, latestFirst);
        }
        PricePoint previous = latestFirst.get(1);
        PricePoint adjusted = new PricePoint(previous.asOf(),
                previous.close().multiply(factor).setScale(4, RoundingMode.HALF_UP), previous.at());
        return of(openQty, lastPrice, lastPriceAsOf, List.of(latestFirst.get(0), adjusted));
    }

    private static boolean hasActionInWindow(@Nullable List<CorporateAction> actions, LocalDate after, LocalDate upTo) {
        if (actions == null) {
            return false;
        }
        for (CorporateAction ca : actions) {
            LocalDate ex = ca.getExDate();
            if (ex != null && ex.isAfter(after) && !ex.isAfter(upTo)) {
                return true;
            }
        }
        return false;
    }

    /** What the position was worth at the previous close (null when there is no day change). */
    @Nullable
    public BigDecimal previousValue(BigDecimal openQty) {
        return previousClose == null ? null : previousClose.multiply(openQty);
    }
}
