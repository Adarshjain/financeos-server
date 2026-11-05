package com.financeos.domain.nps;

import com.financeos.domain.investment.FinancialYear;
import com.financeos.domain.investment.returncalc.XirrCalculator;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The pure ledger maths of NPS: units per scheme, invested amount, this-FY contributions, XIRR. */
public final class NpsCalculator {

    private NpsCalculator() {
    }

    public record AccountTier(UUID accountId, NpsTier tier) {
    }

    /** Units of one scheme in one account and tier; {@code estimated} when any contributing entry has estimated units. */
    public record Holding(UUID accountId, NpsTier tier, String schemeCode, BigDecimal units, boolean estimated) {
    }

    public record Split(BigDecimal self, BigDecimal employer) {
        static final Split ZERO = new Split(BigDecimal.ZERO, BigDecimal.ZERO);

        Split plus(NpsContributor contributor, BigDecimal amount) {
            return contributor == NpsContributor.EMPLOYER ? new Split(self, employer.add(amount))
                    : new Split(self.add(amount), employer);
        }
    }

    /** Entries that count: PENDING ones carry no units yet. */
    private static boolean counts(NpsEntry e) {
        return e.getStatus() != NpsEntryStatus.PENDING;
    }

    /** Units per account, tier and scheme (sum of the lines of every non-PENDING entry); schemes at zero units are left out. */
    public static List<Holding> holdings(Collection<NpsEntry> entries) {
        Map<String, BigDecimal> units = new LinkedHashMap<>();
        Map<String, Boolean> estimated = new LinkedHashMap<>();
        Map<String, Holding> keys = new LinkedHashMap<>();
        for (NpsEntry e : entries) {
            if (!counts(e)) {
                continue;
            }
            for (NpsEntryLine line : e.getLines()) {
                String key = e.getAccount().getId() + "|" + e.getTier() + "|" + line.getSchemeCode();
                units.merge(key, line.getUnits(), BigDecimal::add);
                estimated.merge(key, e.getStatus() == NpsEntryStatus.ESTIMATED, Boolean::logicalOr);
                keys.putIfAbsent(key, new Holding(e.getAccount().getId(), e.getTier(), line.getSchemeCode(), null, false));
            }
        }
        List<Holding> out = new ArrayList<>();
        for (Map.Entry<String, Holding> k : keys.entrySet()) {
            BigDecimal u = units.get(k.getKey());
            if (u.signum() != 0) {
                Holding h = k.getValue();
                out.add(new Holding(h.accountId(), h.tier(), h.schemeCode(), u, estimated.get(k.getKey())));
            }
        }
        return out;
    }

    /**
     * Money put in per account and tier: contributions and opening balances, less withdrawals (their amounts are
     * negative). Charges, rebalances, switches, migrations and adjustments do not move it.
     */
    public static Map<AccountTier, BigDecimal> invested(Collection<NpsEntry> entries) {
        Map<AccountTier, BigDecimal> out = new LinkedHashMap<>();
        for (NpsEntry e : entries) {
            if (counts(e) && movesInvested(e.getType()) && e.getAmount() != null) {
                out.merge(new AccountTier(e.getAccount().getId(), e.getTier()), e.getAmount(), BigDecimal::add);
            }
        }
        return out;
    }

    /**
     * The invested amount (as {@link #invested}) split by who paid it; an entry with no contributor, an opening
     * balance included, counts as SELF.
     */
    public static Split investedSplit(Collection<NpsEntry> entries) {
        Split out = Split.ZERO;
        for (NpsEntry e : entries) {
            if (counts(e) && movesInvested(e.getType()) && e.getAmount() != null) {
                out = out.plus(e.getContributor(), e.getAmount());
            }
        }
        return out;
    }

    static boolean movesInvested(NpsEntryType type) {
        return type == NpsEntryType.CONTRIBUTION || type == NpsEntryType.OPENING || type == NpsEntryType.WITHDRAWAL;
    }

    /** Contributions posted in the financial year, split SELF vs EMPLOYER, per tier (a missing contributor is SELF). */
    public static Map<NpsTier, Split> contributedIn(Collection<NpsEntry> entries, FinancialYear.Range fy) {
        Map<NpsTier, Split> out = new EnumMap<>(NpsTier.class);
        for (NpsEntry e : entries) {
            if (counts(e) && e.getType() == NpsEntryType.CONTRIBUTION && e.getAmount() != null
                    && !e.getPostedDate().isBefore(fy.from()) && !e.getPostedDate().isAfter(fy.to())) {
                out.merge(e.getTier(), Split.ZERO.plus(e.getContributor(), e.getAmount()),
                        (a, b) -> new Split(a.self().add(b.self()), a.employer().add(b.employer())));
            }
        }
        return out;
    }

    /**
     * XIRR in percent over the contributions, opening balances and withdrawals plus the current value as the final
     * inflow; null unless the first such entry is at least 12 months old, or when the flows have no sign change.
     */
    public static BigDecimal xirrPercent(Collection<NpsEntry> entries, BigDecimal currentValue, LocalDate today) {
        List<XirrCalculator.Cashflow> flows = new ArrayList<>();
        LocalDate first = null;
        for (NpsEntry e : entries) {
            if (counts(e) && movesInvested(e.getType()) && e.getAmount() != null && e.getAmount().signum() != 0) {
                flows.add(new XirrCalculator.Cashflow(e.getPostedDate(), e.getAmount().negate()));
                if (first == null || e.getPostedDate().isBefore(first)) {
                    first = e.getPostedDate();
                }
            }
        }
        if (first == null || first.isAfter(today.minusMonths(12)) || currentValue == null || currentValue.signum() <= 0) {
            return null;
        }
        flows.add(new XirrCalculator.Cashflow(today, currentValue));
        Double rate = XirrCalculator.calculateXirr(flows);
        if (rate == null || rate.isNaN() || rate.isInfinite()) {
            return null;
        }
        return BigDecimal.valueOf(rate * 100).setScale(2, RoundingMode.HALF_UP);
    }
}
