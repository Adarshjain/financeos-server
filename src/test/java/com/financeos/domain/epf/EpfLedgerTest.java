package com.financeos.domain.epf;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EpfLedgerTest {

    private final EpfEmployer a = employer();
    private final EpfEmployer b = employer();
    private int seq = 0;

    @Test
    void openingBalanceResetsAndContributionsInterestAndTransfersInAdd() {
        EpfEntry earlier = entry(a, EpfEntryType.CONTRIBUTION, "2025-01-10", 100, 50);
        EpfEntry opening = entry(a, EpfEntryType.OPENING_BALANCE, "2025-04-01", 1000, 400);
        EpfEntry contribution = entry(a, EpfEntryType.CONTRIBUTION, "2025-05-10", 30, 20);
        EpfEntry interest = entry(a, EpfEntryType.INTEREST, "2025-06-01", 10, 5);
        EpfEntry transferIn = entry(a, EpfEntryType.TRANSFER_IN, "2025-07-01", 200, 100);

        EpfLedger ledger = EpfLedger.of(List.of(transferIn, interest, contribution, opening, earlier));

        assertBalance(ledger.runningBalance(earlier), 100, 50);
        assertBalance(ledger.runningBalance(opening), 1000, 400); // reset, not 1100/450
        assertBalance(ledger.runningBalance(contribution), 1030, 420);
        assertBalance(ledger.runningBalance(interest), 1040, 425);
        assertBalance(ledger.runningBalance(transferIn), 1240, 525);
        assertBalance(ledger.balance(a.getId()), 1240, 525);
        assertEquals(0, new BigDecimal("1765").compareTo(ledger.total().total()));
    }

    @Test
    void transfersOutAndEpfWithdrawalsSubtractButPensionWithdrawalAndPensionAmountNeverCount() {
        EpfEntry contribution = entry(a, EpfEntryType.CONTRIBUTION, "2025-05-10", 500, 300);
        contribution.setPensionAmount(new BigDecimal("125"));
        EpfEntry partial = withdrawal(a, EpfWithdrawalKind.PARTIAL, "2025-06-01", 100, 50, 0);
        EpfEntry pension = withdrawal(a, EpfWithdrawalKind.PENSION, "2025-06-02", 0, 0, 900);
        EpfEntry out = entry(a, EpfEntryType.TRANSFER_OUT, "2025-06-03", 40, 10);
        EpfEntry settlement = withdrawal(a, EpfWithdrawalKind.FINAL_SETTLEMENT, "2025-06-04", 360, 240, 0);

        EpfLedger ledger = EpfLedger.of(List.of(contribution, partial, pension, out, settlement));

        assertBalance(ledger.runningBalance(contribution), 500, 300); // pension_amount is outside the balance
        assertBalance(ledger.runningBalance(partial), 400, 250);
        assertBalance(ledger.runningBalance(pension), 400, 250);
        assertBalance(ledger.runningBalance(out), 360, 240);
        assertBalance(ledger.runningBalance(settlement), 0, 0);
    }

    @Test
    void sameDayEntriesApplyInTypeOrderThenCreationOrder() {
        // A withdrawal and a contribution on one date: contribution first, so the balance never dips below zero.
        EpfEntry withdrawal = withdrawal(a, EpfWithdrawalKind.PARTIAL, "2025-06-01", 100, 0, 0);
        EpfEntry contribution = entry(a, EpfEntryType.CONTRIBUTION, "2025-06-01", 100, 0);
        EpfEntry opening = entry(a, EpfEntryType.OPENING_BALANCE, "2025-06-01", 10, 0);
        EpfEntry laterContribution = entry(a, EpfEntryType.CONTRIBUTION, "2025-06-01", 1, 0);

        EpfLedger ledger = EpfLedger.of(List.of(withdrawal, laterContribution, contribution, opening));

        assertEquals(List.of(opening, contribution, laterContribution, withdrawal), ledger.ordered(a.getId()));
        assertBalance(ledger.runningBalance(opening), 10, 0);
        assertBalance(ledger.runningBalance(withdrawal), 11, 0);
    }

    @Test
    void employersAreTrackedSeparatelyAndATransferPairMovesMoneyBetweenThem() {
        EpfEntry contribution = entry(a, EpfEntryType.CONTRIBUTION, "2025-05-10", 1000, 500);
        EpfEntry out = entry(a, EpfEntryType.TRANSFER_OUT, "2025-06-01", 1000, 500);
        EpfEntry in = entry(b, EpfEntryType.TRANSFER_IN, "2025-06-01", 1000, 500);

        EpfLedger ledger = EpfLedger.of(List.of(contribution, out, in));

        assertBalance(ledger.balance(a.getId()), 0, 0);
        assertBalance(ledger.balance(b.getId()), 1000, 500);
        assertEquals(0, new BigDecimal("1500").compareTo(ledger.total().total()));
        assertBalance(ledger.runningBalance(in), 1000, 500);
    }

    @Test
    void balanceAtEndOfMonthCountsEntriesCreditedUpToThatMonthEnd() {
        EpfEntry opening = entry(a, EpfEntryType.OPENING_BALANCE, "2026-03-31", 1000, 0);
        EpfEntry contribution = entry(a, EpfEntryType.CONTRIBUTION, "2026-05-10", 100, 0);
        EpfLedger ledger = EpfLedger.of(List.of(opening, contribution));

        assertBalance(ledger.balanceAtEndOfMonth(a.getId(), YearMonth.of(2026, 3)), 1000, 0);
        assertBalance(ledger.balanceAtEndOfMonth(a.getId(), YearMonth.of(2026, 4)), 1000, 0);
        assertBalance(ledger.balanceAtEndOfMonth(a.getId(), YearMonth.of(2026, 5)), 1100, 0);
        assertBalance(ledger.balanceAtEndOfMonth(a.getId(), YearMonth.of(2026, 2)), 0, 0);
        assertBalance(ledger.balanceAtEndOfMonth(b.getId(), YearMonth.of(2026, 5)), 0, 0);
    }

    @Test
    void grossAndReceivedOfWithdrawals() {
        EpfEntry partial = withdrawal(a, EpfWithdrawalKind.PARTIAL, "2025-06-01", 700, 300, 0);
        EpfEntry withTds = withdrawal(a, EpfWithdrawalKind.FINAL_SETTLEMENT, "2025-06-01", 700, 300, 0);
        withTds.setTdsAmount(new BigDecimal("80"));
        EpfEntry pension = withdrawal(a, EpfWithdrawalKind.PENSION, "2025-06-01", 0, 0, 450);

        assertEquals(0, new BigDecimal("1000").compareTo(EpfLedger.gross(partial)));
        assertEquals(0, new BigDecimal("1000").compareTo(EpfLedger.received(partial))); // no TDS counts as none
        assertEquals(0, new BigDecimal("920").compareTo(EpfLedger.received(withTds)));
        assertEquals(0, new BigDecimal("450").compareTo(EpfLedger.gross(pension)));
    }

    // ------------------------------------------------------------------ helpers

    private static EpfEmployer employer() {
        EpfEmployer e = new EpfEmployer();
        e.setId(UUID.randomUUID());
        return e;
    }

    private EpfEntry entry(EpfEmployer employer, EpfEntryType type, String creditedOn, int employee, int employerShare) {
        EpfEntry e = new EpfEntry();
        e.setId(UUID.randomUUID());
        e.setEmployer(employer);
        e.setType(type);
        e.setCreditedOn(LocalDate.parse(creditedOn));
        e.setEmployeeAmount(BigDecimal.valueOf(employee));
        e.setEmployerAmount(BigDecimal.valueOf(employerShare));
        e.setCreatedAt(Instant.ofEpochSecond(1_700_000_000L + seq++));
        return e;
    }

    private EpfEntry withdrawal(EpfEmployer employer, EpfWithdrawalKind kind, String creditedOn, int employee,
                                int employerShare, int pension) {
        EpfEntry e = entry(employer, EpfEntryType.WITHDRAWAL, creditedOn, employee, employerShare);
        e.setWithdrawalKind(kind);
        e.setPensionAmount(BigDecimal.valueOf(pension));
        return e;
    }

    private static void assertBalance(EpfLedger.Balance balance, int employee, int employer) {
        assertEquals(0, BigDecimal.valueOf(employee).compareTo(balance.employee()), "employee " + balance);
        assertEquals(0, BigDecimal.valueOf(employer).compareTo(balance.employer()), "employer " + balance);
    }
}
