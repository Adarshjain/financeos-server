package com.financeos.domain.nps;

import com.financeos.domain.investment.FinancialYear;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NpsCalculatorTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 5);
    private final NpsAccount account = account();

    private static NpsAccount account() {
        NpsAccount a = new NpsAccount();
        a.setId(UUID.randomUUID());
        return a;
    }

    private NpsEntry entry(NpsTier tier, NpsEntryType type, NpsContributor by, String amount, String date,
                           NpsEntryStatus status, Object... schemeUnits) {
        NpsEntry e = new NpsEntry();
        e.setId(UUID.randomUUID());
        e.setAccount(account);
        e.setTier(tier);
        e.setType(type);
        e.setContributor(by);
        e.setAmount(amount == null ? null : new BigDecimal(amount));
        e.setPostedDate(LocalDate.parse(date));
        e.setStatus(status);
        for (int i = 0; i < schemeUnits.length; i += 2) {
            e.addLine(new NpsEntryLine((String) schemeUnits[i], new BigDecimal((String) schemeUnits[i + 1]), null, null));
        }
        return e;
    }

    private NpsEntry contribution(String amount, String date, NpsContributor by, String scheme, String units) {
        return entry(NpsTier.I, NpsEntryType.CONTRIBUTION, by, amount, date, NpsEntryStatus.CONFIRMED, scheme, units);
    }

    @Test
    void unitsSumPerSchemeAndPendingEntriesCountForNothing() {
        List<NpsEntry> entries = List.of(
                contribution("1000", "2026-04-10", NpsContributor.SELF, "SM1", "10.5000"),
                contribution("500", "2026-05-10", NpsContributor.SELF, "SM1", "4.5000"),
                entry(NpsTier.I, NpsEntryType.CHARGE, null, null, "2026-06-01", NpsEntryStatus.CONFIRMED, "SM1", "-0.2500"),
                entry(NpsTier.I, NpsEntryType.CONTRIBUTION, NpsContributor.SELF, "999", "2026-06-02",
                        NpsEntryStatus.PENDING, "SM1", "99.0000"));

        List<NpsCalculator.Holding> holdings = NpsCalculator.holdings(entries);

        assertEquals(1, holdings.size());
        assertEquals(0, new BigDecimal("14.7500").compareTo(holdings.get(0).units()));
        assertFalse(holdings.get(0).estimated());
    }

    @Test
    void aSchemeFullySwitchedOutLeavesNoHolding() {
        List<NpsEntry> entries = List.of(
                contribution("1000", "2026-04-10", NpsContributor.SELF, "SM1", "10.0000"),
                entry(NpsTier.I, NpsEntryType.SWITCH, null, null, "2026-07-01", NpsEntryStatus.CONFIRMED,
                        "SM1", "-10.0000", "SM2", "7.0000"));

        List<NpsCalculator.Holding> holdings = NpsCalculator.holdings(entries);

        assertEquals(List.of("SM2"), holdings.stream().map(NpsCalculator.Holding::schemeCode).toList());
    }

    @Test
    void estimatedFlagCoversOnlyHoldingsWithAnEstimatedEntry() {
        List<NpsEntry> entries = List.of(
                entry(NpsTier.I, NpsEntryType.CONTRIBUTION, NpsContributor.SELF, "100", "2026-04-10",
                        NpsEntryStatus.ESTIMATED, "SM1", "1.0000"),
                contribution("100", "2026-04-11", NpsContributor.SELF, "SM2", "2.0000"));

        Map<String, Boolean> byScheme = new java.util.HashMap<>();
        NpsCalculator.holdings(entries).forEach(h -> byScheme.put(h.schemeCode(), h.estimated()));

        assertTrue(byScheme.get("SM1"));
        assertFalse(byScheme.get("SM2"));
    }

    @Test
    void investedCountsContributionsOpeningAndWithdrawalsOnly() {
        List<NpsEntry> entries = List.of(
                entry(NpsTier.I, NpsEntryType.OPENING, null, "5000", "2022-04-01", NpsEntryStatus.CONFIRMED, "SM1", "50.0000"),
                contribution("1000", "2026-04-10", NpsContributor.SELF, "SM1", "10.0000"),
                entry(NpsTier.I, NpsEntryType.WITHDRAWAL, null, "-2000", "2026-08-01", NpsEntryStatus.CONFIRMED, "SM1", "-20.0000"),
                entry(NpsTier.I, NpsEntryType.CHARGE, null, "-40", "2026-09-01", NpsEntryStatus.CONFIRMED, "SM1", "-0.4000"),
                entry(NpsTier.I, NpsEntryType.REBALANCE, null, "300", "2026-09-02", NpsEntryStatus.CONFIRMED, "SM1", "3.0000"),
                entry(NpsTier.I, NpsEntryType.ADJUSTMENT, null, "11", "2026-09-03", NpsEntryStatus.CONFIRMED, "SM1", "0.1000"),
                entry(NpsTier.II, NpsEntryType.CONTRIBUTION, NpsContributor.SELF, "700", "2026-09-04", NpsEntryStatus.CONFIRMED, "SM9", "7.0000"));

        Map<NpsCalculator.AccountTier, BigDecimal> invested = NpsCalculator.invested(entries);

        assertEquals(0, new BigDecimal("4000").compareTo(invested.get(new NpsCalculator.AccountTier(account.getId(), NpsTier.I))));
        assertEquals(0, new BigDecimal("700").compareTo(invested.get(new NpsCalculator.AccountTier(account.getId(), NpsTier.II))));
    }

    @Test
    void financialYearSplitsSelfFromEmployerPerTier() {
        List<NpsEntry> entries = List.of(
                contribution("1000", "2026-04-01", NpsContributor.SELF, "SM1", "1.0000"),
                contribution("400", "2026-10-01", NpsContributor.EMPLOYER, "SM1", "1.0000"),
                contribution("250", "2026-11-01", null, "SM1", "1.0000"),
                contribution("9999", "2026-03-31", NpsContributor.SELF, "SM1", "1.0000"),
                entry(NpsTier.II, NpsEntryType.CONTRIBUTION, NpsContributor.SELF, "300", "2027-03-31",
                        NpsEntryStatus.CONFIRMED, "SM9", "1.0000"),
                entry(NpsTier.II, NpsEntryType.CONTRIBUTION, NpsContributor.SELF, "50", "2027-04-01",
                        NpsEntryStatus.CONFIRMED, "SM9", "1.0000"));

        Map<NpsTier, NpsCalculator.Split> split = NpsCalculator.contributedIn(entries, FinancialYear.range(2026));

        assertEquals(0, new BigDecimal("1250").compareTo(split.get(NpsTier.I).self()));
        assertEquals(0, new BigDecimal("400").compareTo(split.get(NpsTier.I).employer()));
        assertEquals(0, new BigDecimal("300").compareTo(split.get(NpsTier.II).self()));
        assertEquals(0, BigDecimal.ZERO.compareTo(split.get(NpsTier.II).employer()));
    }

    @Test
    void xirrIsNullUntilTheFirstEntryIsTwelveMonthsOld() {
        List<NpsEntry> young = List.of(contribution("1000", TODAY.minusMonths(11).toString(), NpsContributor.SELF, "SM1", "1.0000"));
        assertNull(NpsCalculator.xirrPercent(young, new BigDecimal("1100"), TODAY));

        List<NpsEntry> old = List.of(contribution("1000", TODAY.minusMonths(12).toString(), NpsContributor.SELF, "SM1", "1.0000"));
        BigDecimal xirr = NpsCalculator.xirrPercent(old, new BigDecimal("1100"), TODAY);
        assertNotNull(xirr);
        assertEquals(10.0, xirr.doubleValue(), 0.2);
    }

    @Test
    void xirrNeedsAFlowOutAndAPositiveValue() {
        assertNull(NpsCalculator.xirrPercent(List.of(), new BigDecimal("1100"), TODAY));
        List<NpsEntry> old = List.of(contribution("1000", "2024-01-01", NpsContributor.SELF, "SM1", "1.0000"));
        assertNull(NpsCalculator.xirrPercent(old, BigDecimal.ZERO, TODAY));
    }
}
