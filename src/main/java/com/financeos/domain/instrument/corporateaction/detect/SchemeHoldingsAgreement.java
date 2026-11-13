package com.financeos.domain.instrument.corporateaction.detect;

import org.springframework.lang.Nullable;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Does what the company's notices say a demerger or merger should have given a broker account match what that
 * account's holdings show? Pure arithmetic over what the caller loaded: the parent quantity held the day before, the
 * latest holdings snapshot of the account and the account's trades in the child (or acquirer).
 */
public final class SchemeHoldingsAgreement {

    public static final String SNAPSHOT = "SNAPSHOT";
    public static final String TRADEBOOK = "TRADEBOOK";
    public static final String NONE = "NONE";

    /** One row of an account's holdings snapshot (every row of a snapshot shares its date). */
    public record SnapRow(@Nullable String isin, BigDecimal quantity, LocalDate asOf) {
    }

    /** One trade of the child or acquirer in the account, in the order they happened. */
    public record Trade(boolean buy, LocalDate date, BigDecimal quantity) {
    }

    /**
     * @param expectedQty for a snapshot: the quantity it should show; for a tradebook: the quantity the notices give
     * @param foundQty    the quantity the snapshot shows, or the quantity sold before any buy
     * @param neutral     the notices give this account no whole child share: it is no evidence either way and never counts
     */
    public record Check(UUID brokerAccountId, String accountName, String source, @Nullable LocalDate asOf, BigDecimal expectedQty,
                        BigDecimal foundQty, boolean agrees, boolean neutral) {
        public Check(UUID brokerAccountId, String accountName, String source, @Nullable LocalDate asOf, BigDecimal expectedQty,
                     BigDecimal foundQty, boolean agrees) {
            this(brokerAccountId, accountName, source, asOf, expectedQty, foundQty, agrees, false);
        }

        Check asNeutral(boolean neutral) {
            return neutral == this.neutral ? this : new Check(brokerAccountId, accountName, source, asOf, expectedQty, foundQty, agrees, neutral);
        }
    }

    private SchemeHoldingsAgreement() {
    }

    /** {@code floor(parentQty x to / from)}: the whole shares of the child the notices give for the parent quantity. */
    public static BigDecimal floorShares(BigDecimal parentQty, int from, int to) {
        if (from <= 0 || to <= 0 || parentQty.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        return parentQty.multiply(BigDecimal.valueOf(to)).divide(BigDecimal.valueOf(from), 0, RoundingMode.FLOOR);
    }

    /**
     * A demerger in one account. Snapshot: the latest snapshot, dated on or after the child's listing, shows the child
     * quantity expected minus the account's child sells plus its child buys recorded strictly before the snapshot date (any trade on that date voids the snapshot evidence). Tradebook:
     * the child sells on or after the child's listing (or the ex-date, the later) that came before any child buy add up to exactly
     * the expected quantity; sells before that day are no evidence. Either agreeing is enough. An account the notices give no whole
     * child share is neutral.
     */
    public static Check demerger(UUID accountId, String accountName, BigDecimal parentQty, int from, int to, String childIsin,
                                 @Nullable LocalDate listedOn, List<SnapRow> snapshot, List<Trade> childTrades) {
        return demerger(accountId, accountName, parentQty, from, to, childIsin, listedOn, null, snapshot, childTrades);
    }

    /** As above with the demerger's ex-date, the earliest day a child sell can count. */
    public static Check demerger(UUID accountId, String accountName, BigDecimal parentQty, int from, int to, String childIsin,
                                 @Nullable LocalDate listedOn, @Nullable LocalDate exDate, List<SnapRow> snapshot, List<Trade> childTrades) {
        Check c = demergerCheck(accountId, accountName, parentQty, from, to, childIsin, listedOn, exDate, snapshot, childTrades);
        return c.asNeutral(floorShares(parentQty, from, to).signum() <= 0);
    }

    private static Check demergerCheck(UUID accountId, String accountName, BigDecimal parentQty, int from, int to, String childIsin,
                                       @Nullable LocalDate listedOn, @Nullable LocalDate exDate, List<SnapRow> snapshot,
                                       List<Trade> childTrades) {
        BigDecimal expected = floorShares(parentQty, from, to);
        LocalDate sellsFrom = listedOn == null ? exDate : (exDate == null || listedOn.isAfter(exDate) ? listedOn : exDate);
        Check snapshotCheck = null;
        if (!snapshot.isEmpty() && listedOn != null && !snapshot.get(0).asOf().isBefore(listedOn)) {
            LocalDate asOf = snapshot.get(0).asOf();
            BigDecimal found = quantityOf(snapshot, childIsin);
            if (tradedOn(childTrades, asOf)) {
                snapshotCheck = new Check(accountId, accountName, NONE, asOf, expected, found, false); // trade on the holdings file date
            }
            BigDecimal sells = BigDecimal.ZERO;
            BigDecimal buys = BigDecimal.ZERO;
            for (Trade t : childTrades) {
                if (t.date().isBefore(asOf)) {
                    if (t.buy()) {
                        buys = buys.add(t.quantity());
                    } else {
                        sells = sells.add(t.quantity());
                    }
                }
            }
            BigDecimal shouldShow = expected.subtract(sells).add(buys);
            if (snapshotCheck == null) {
                snapshotCheck = new Check(accountId, accountName, SNAPSHOT, asOf, shouldShow, found, found.compareTo(shouldShow) == 0);
                if (snapshotCheck.agrees()) {
                    return snapshotCheck;
                }
            }
        }
        BigDecimal soldFirst = BigDecimal.ZERO;
        LocalDate lastSell = null;
        for (Trade t : childTrades) {
            if (t.buy()) {
                break;
            }
            if (sellsFrom != null && t.date().isBefore(sellsFrom)) {
                continue; // before the child existed: not the child's shares
            }
            soldFirst = soldFirst.add(t.quantity());
            lastSell = t.date();
        }
        if (expected.signum() > 0 && soldFirst.compareTo(expected) == 0) {
            return new Check(accountId, accountName, TRADEBOOK, lastSell, expected, soldFirst, true);
        }
        return snapshotCheck != null ? snapshotCheck : new Check(accountId, accountName, NONE, null, expected, BigDecimal.ZERO, false);
    }

    /**
     * A merger in one account: the latest snapshot, dated on or after the record date, shows exactly the acquirer quantity
     * the app already had by the snapshot date plus {@code floor(parentQty x to / from)}; trades strictly before the snapshot date count, and a trade on the
     * snapshot date makes it no evidence.
     */
    public static Check merger(UUID accountId, String accountName, BigDecimal parentQty, int from, int to, String acquirerIsin,
                               @Nullable LocalDate recordDate, List<SnapRow> snapshot, List<Trade> acquirerTrades) {
        BigDecimal given = floorShares(parentQty, from, to);
        return mergerCheck(accountId, accountName, given, acquirerIsin, recordDate, snapshot, acquirerTrades).asNeutral(given.signum() <= 0);
    }

    private static Check mergerCheck(UUID accountId, String accountName, BigDecimal given, String acquirerIsin,
                                     @Nullable LocalDate recordDate, List<SnapRow> snapshot, List<Trade> acquirerTrades) {
        if (snapshot.isEmpty() || recordDate == null || snapshot.get(0).asOf().isBefore(recordDate)) {
            return new Check(accountId, accountName, NONE, null, given, BigDecimal.ZERO, false);
        }
        LocalDate asOf = snapshot.get(0).asOf();
        if (tradedOn(acquirerTrades, asOf)) {
            return new Check(accountId, accountName, NONE, asOf, given, BigDecimal.ZERO, false); // trade on the holdings file date
        }
        BigDecimal own = BigDecimal.ZERO;
        for (Trade t : acquirerTrades) {
            if (t.date().isBefore(asOf)) {
                own = t.buy() ? own.add(t.quantity()) : own.subtract(t.quantity());
            }
        }
        BigDecimal shouldShow = own.add(given);
        BigDecimal found = quantityOf(snapshot, acquirerIsin);
        return new Check(accountId, accountName, SNAPSHOT, asOf, shouldShow, found, found.compareTo(shouldShow) == 0);
    }

    /** True only when at least one account is evidence (not neutral) and every such account agrees; neutral accounts are ignored. */
    public static boolean allAgree(List<Check> checks) {
        boolean any = false;
        for (Check c : checks) {
            if (c.neutral()) {
                continue;
            }
            if (!c.agrees()) {
                return false;
            }
            any = true;
        }
        return any;
    }

    /** The file may or may not include a trade made on its own date, so such an account has no snapshot evidence. */
    private static boolean tradedOn(List<Trade> trades, LocalDate asOf) {
        return trades.stream().anyMatch(t -> t.date().equals(asOf));
    }

    private static BigDecimal quantityOf(List<SnapRow> snapshot, String isin) {
        BigDecimal sum = BigDecimal.ZERO;
        for (SnapRow r : snapshot) {
            if (r.isin() != null && r.isin().trim().equalsIgnoreCase(isin)) {
                sum = sum.add(r.quantity());
            }
        }
        return sum;
    }
}
