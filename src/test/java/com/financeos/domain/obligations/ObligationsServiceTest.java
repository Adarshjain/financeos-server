package com.financeos.domain.obligations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.financeos.api.loan.dto.InstallmentDto;
import com.financeos.api.loan.dto.LoanResponse;
import com.financeos.api.obligations.dto.ObligationItemDto;
import com.financeos.core.exception.ValidationException;
import com.financeos.core.time.AppTime;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.account.AccountType;
import com.financeos.domain.lending.LendingDirection;
import com.financeos.domain.lending.LendingService;
import com.financeos.domain.loan.LoanService;
import com.financeos.domain.loan.LoanStatus;
import com.financeos.domain.notification.bill.BillStatus;
import com.financeos.domain.notification.bill.CardBill;
import com.financeos.domain.notification.bill.CardBillService;
import com.financeos.domain.notification.bill.PaidSource;
import com.financeos.domain.statement.Statement;
import com.financeos.domain.statement.StatementRepository;
import com.financeos.domain.statement.StatementVerdict;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

class ObligationsServiceTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 8);
    /** Default 3-month window end. */
    private static final LocalDate MAX_DATE = TODAY.plusMonths(3);

    private LoanService loanService;
    private LendingService lendingService;
    private CardBillService cardBillService;
    private AccountRepository accountRepository;
    private StatementRepository statementRepository;
    private ObligationsService service;
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        AppTime.useClock(Clock.fixed(TODAY.atTime(10, 0).atZone(IST).toInstant(), IST));
        loanService = mock(LoanService.class);
        lendingService = mock(LendingService.class);
        cardBillService = mock(CardBillService.class);
        accountRepository = mock(AccountRepository.class);
        statementRepository = mock(StatementRepository.class);
        when(loanService.getLoans(eq(LoanStatus.active), any())).thenReturn(new PageImpl<>(List.of()));
        service = new ObligationsService(loanService, lendingService, cardBillService, accountRepository, statementRepository);
    }

    @AfterEach
    void tearDown() {
        AppTime.reset();
    }

    // ------------------------------------------------------------------ EMI

    @Test
    void emiRowCarriesTitleHrefStatusAndDaysUntil() {
        UUID loanId = UUID.randomUUID();
        loans(loan(loanId, "Home Loan"));
        when(loanService.getLoanSchedule(loanId)).thenReturn(List.of(inst(5, TODAY.plusDays(4), "25000", "upcoming")));

        List<ObligationItemDto> items = upcoming(3, Set.of(ObligationsService.KIND_EMI));

        assertEquals(1, items.size());
        ObligationItemDto i = items.get(0);
        assertEquals("emi", i.type());
        assertEquals(TODAY.plusDays(4), i.date());
        assertEquals(new BigDecimal("25000"), i.amount());
        assertEquals("due_soon", i.status());
        assertEquals(loanId, i.loanId());
        assertEquals("Home Loan", i.loanName());
        assertEquals(5, i.installmentSeq());
        assertEquals("Home Loan EMI #5", i.title());
        assertEquals("Home Loan", i.accountName());
        assertNull(i.accountId());
        assertNull(i.statementId());
        assertEquals("/loans/" + loanId + "?installment=5", i.href());
        assertEquals(4L, i.daysUntil());
        verify(loanService).getLoans(LoanStatus.active, Pageable.unpaged());
    }

    @Test
    void emiWithoutSequenceHasPlainTitleAndLoanHref() {
        UUID loanId = UUID.randomUUID();
        loans(loan(loanId, "Car Loan"));
        when(loanService.getLoanSchedule(loanId)).thenReturn(List.of(inst(null, TODAY.plusDays(20), "9000", "upcoming")));

        ObligationItemDto i = upcoming(3, Set.of("emi")).get(0);

        assertEquals("Car Loan EMI", i.title());
        assertEquals("/loans/" + loanId, i.href());
        assertNull(i.installmentSeq());
        assertEquals("upcoming", i.status());
    }

    @Test
    void emiSkipsSettledUndatedAndOutOfWindowInstallmentsButKeepsOverdueOnes() {
        UUID loanId = UUID.randomUUID();
        loans(loan(loanId, "Home Loan"));
        when(loanService.getLoanSchedule(loanId)).thenReturn(List.of(
                inst(1, TODAY.minusMonths(6), "100", "overdue"),   // long overdue: kept
                inst(2, TODAY.minusDays(1), "100", "settled"),     // settled: skipped
                inst(3, null, "100", "upcoming"),                  // no date: skipped
                inst(4, MAX_DATE, "100", "upcoming"),              // on the window end: kept
                inst(5, MAX_DATE.plusDays(1), "100", "upcoming")   // past the window: skipped
        ));

        List<ObligationItemDto> items = upcoming(3, Set.of("emi"));

        assertEquals(List.of(1, 4), items.stream().map(ObligationItemDto::installmentSeq).toList());
        assertEquals("overdue", items.get(0).status());
        assertEquals(-183L, items.get(0).daysUntil());
        assertEquals("upcoming", items.get(1).status());
    }

    // ------------------------------------------------------------------ lending

    @Test
    void lendingRowsTitleByDirectionAndLinkToTheCounterparty() {
        UUID lentCp = UUID.randomUUID();
        UUID borrowedCp = UUID.randomUUID();
        UUID lendingId = UUID.randomUUID();
        when(lendingService.getUpcomingLendingObligations(TODAY, MAX_DATE)).thenReturn(List.of(
                rawLending(TODAY.plusDays(2), "5000", lendingId, lentCp, "Asha", LendingDirection.lent),
                rawLending(TODAY.plusDays(30), "1200", null, borrowedCp, "Ravi", LendingDirection.borrowed)));

        List<ObligationItemDto> items = upcoming(3, Set.of("lending_due"));

        assertEquals(2, items.size());
        ObligationItemDto lent = items.get(0);
        assertEquals("lending_due", lent.type());
        assertEquals("Asha owes you", lent.title());
        assertEquals("/loans/lendings/" + lentCp, lent.href());
        assertEquals("Asha", lent.accountName());
        assertEquals("Asha", lent.counterpartyName());
        assertEquals(lentCp, lent.counterpartyId());
        assertEquals(lendingId, lent.lendingId());
        assertEquals(LendingDirection.lent, lent.direction());
        assertEquals(new BigDecimal("5000"), lent.amount());
        assertEquals("due_soon", lent.status());
        assertEquals(2L, lent.daysUntil());

        ObligationItemDto borrowed = items.get(1);
        assertEquals("You owe Ravi", borrowed.title());
        assertEquals("upcoming", borrowed.status());
        assertEquals(30L, borrowed.daysUntil());
    }

    @Test
    void lendingWithoutCounterpartyIdLinksToTheLendingsList() {
        when(lendingService.getUpcomingLendingObligations(TODAY, MAX_DATE)).thenReturn(List.of(
                rawLending(TODAY.minusDays(3), "700", null, null, "Old", LendingDirection.lent)));

        ObligationItemDto i = upcoming(3, Set.of("lending_due")).get(0);

        assertEquals("/loans/lendings", i.href());
        assertEquals("overdue", i.status());
        assertEquals(-3L, i.daysUntil());
    }

    // ------------------------------------------------------------------ card bills

    @Test
    void cardBillRowCarriesRemainingAmountTitleAndUpcomingHref() {
        UUID accountId = UUID.randomUUID();
        UUID stmt = UUID.randomUUID();
        when(cardBillService.listBills(userId)).thenReturn(List.of(
                bill(accountId, "HDFC Regalia", "4321", stmt, BillStatus.PARTIAL, TODAY.plusDays(7), "10000", "2500")));

        ObligationItemDto i = upcoming(3, Set.of("card_bill")).get(0);

        assertEquals("card_bill", i.type());
        assertEquals(new BigDecimal("2500"), i.amount());
        assertEquals("HDFC Regalia ••4321 bill", i.title());
        assertEquals(accountId, i.accountId());
        assertEquals("HDFC Regalia", i.accountName());
        assertEquals(stmt, i.statementId());
        assertEquals("/upcoming?bill=" + stmt, i.href());
        assertEquals("due_soon", i.status());
        assertEquals(7L, i.daysUntil());
    }

    @Test
    void cardBillAmountFallsBackToTheTotalWhenNothingRemainingIsKnown() {
        when(cardBillService.listBills(userId)).thenReturn(List.of(
                bill(UUID.randomUUID(), "Axis", "1111", UUID.randomUUID(), BillStatus.OPEN, TODAY.plusDays(10), "8000", null)));

        ObligationItemDto i = upcoming(3, Set.of("card_bill")).get(0);

        assertEquals(new BigDecimal("8000"), i.amount());
        assertEquals("upcoming", i.status());
    }

    @Test
    void cardBillTitleOmitsBlankOrMissingLast4() {
        when(cardBillService.listBills(userId)).thenReturn(List.of(
                bill(UUID.randomUUID(), "Blank", "  ", UUID.randomUUID(), BillStatus.OPEN, TODAY.plusDays(1), "1", null),
                bill(UUID.randomUUID(), "Missing", null, UUID.randomUUID(), BillStatus.OPEN, TODAY.plusDays(2), "1", null)));

        List<ObligationItemDto> items = upcoming(3, Set.of("card_bill"));

        assertEquals(List.of("Blank bill", "Missing bill"), items.stream().map(ObligationItemDto::title).toList());
    }

    @Test
    void onlyBillsStillWantingAPaymentAreListed() {
        List<CardBill> bills = new ArrayList<>();
        for (BillStatus s : BillStatus.values()) {
            bills.add(bill(UUID.randomUUID(), s.name(), "0000", UUID.randomUUID(), s, TODAY.plusDays(3), "100", null));
        }
        bills.add(bill(UUID.randomUUID(), "NullStatus", "0000", UUID.randomUUID(), null, TODAY.plusDays(3), "100", null));
        bills.add(bill(UUID.randomUUID(), "NoStatement", "0000", null, BillStatus.OPEN, TODAY.plusDays(3), "100", null));
        when(cardBillService.listBills(userId)).thenReturn(bills);

        Set<String> listed = Set.copyOf(upcoming(3, Set.of("card_bill")).stream().map(ObligationItemDto::accountName).toList());

        assertEquals(Set.of("OPEN", "PARTIAL", "OVERDUE", "DUE_UNKNOWN"), listed);
    }

    @Test
    void overdueBillIsOverdueAndABillDueAfterTheWindowIsDropped() {
        when(cardBillService.listBills(userId)).thenReturn(List.of(
                bill(UUID.randomUUID(), "Late", "1", UUID.randomUUID(), BillStatus.OVERDUE, TODAY.minusDays(2), "500", "500"),
                bill(UUID.randomUUID(), "OnEdge", "2", UUID.randomUUID(), BillStatus.OPEN, MAX_DATE, "500", null),
                bill(UUID.randomUUID(), "Far", "3", UUID.randomUUID(), BillStatus.OPEN, MAX_DATE.plusDays(1), "500", null)));

        List<ObligationItemDto> items = upcoming(3, Set.of("card_bill"));

        assertEquals(List.of("Late", "OnEdge"), items.stream().map(ObligationItemDto::accountName).toList());
        assertEquals("overdue", items.get(0).status());
        assertEquals(-2L, items.get(0).daysUntil());
    }

    @Test
    void billWithUnknownDueDateIsListedUpcomingWithNoDaysUntil() {
        when(cardBillService.listBills(userId)).thenReturn(List.of(
                bill(UUID.randomUUID(), "Unknown", "9", UUID.randomUUID(), BillStatus.DUE_UNKNOWN, null, null, null)));

        ObligationItemDto i = upcoming(3, Set.of("card_bill")).get(0);

        assertNull(i.date());
        assertNull(i.amount());
        assertEquals("upcoming", i.status());
        assertNull(i.daysUntil());
    }

    // ------------------------------------------------------------------ statement expected

    @Test
    void statementExpectedAtTheCloseOfTheNextCycle() {
        Account card = card("Axis Ace", null);
        accounts(card);
        statements(card, stmt(LocalDate.of(2026, 8, 16), LocalDate.of(2026, 9, 15), StatementVerdict.AUTO_INGEST));

        ObligationItemDto i = upcoming(3, Set.of("statement_expected")).get(0);

        assertEquals("statement_expected", i.type());
        assertEquals(LocalDate.of(2026, 10, 15), i.date());
        assertNull(i.amount());
        assertEquals("due_soon", i.status());
        assertEquals(7L, i.daysUntil());
        assertEquals("Axis Ace statement expected", i.title());
        assertEquals(card.getId(), i.accountId());
        assertEquals("Axis Ace", i.accountName());
        assertNull(i.statementId());
        assertEquals("/transactions/import", i.href());
        verify(accountRepository).findByUserIdAndType(userId, AccountType.credit_card);
    }

    @Test
    void lateStatementStaysDueSoonThroughTheFiveDayGrace() {
        Account card = card("Grace", null);
        accounts(card);
        // closes on the 3rd: next statement expected 2026-10-03 = today - 5
        statements(card, stmt(LocalDate.of(2026, 8, 4), LocalDate.of(2026, 9, 3), StatementVerdict.AUTO_INGEST));

        ObligationItemDto i = upcoming(3, Set.of("statement_expected")).get(0);

        assertEquals(LocalDate.of(2026, 10, 3), i.date());
        assertEquals("due_soon", i.status());
        assertEquals(-5L, i.daysUntil());
    }

    @Test
    void statementIsOverdueOnceTheGraceHasPassed() {
        Account card = card("Late", null);
        accounts(card);
        // closes on the 2nd: next statement expected 2026-10-02 = today - 6
        statements(card, stmt(LocalDate.of(2026, 8, 3), LocalDate.of(2026, 9, 2), StatementVerdict.AUTO_INGEST));

        ObligationItemDto i = upcoming(3, Set.of("statement_expected")).get(0);

        assertEquals(LocalDate.of(2026, 10, 2), i.date());
        assertEquals("overdue", i.status());
    }

    @Test
    void closedCardsAreSkippedButACardClosingLaterIsKept() {
        Account closedToday = card("ClosedToday", TODAY);
        Account closedBefore = card("ClosedBefore", TODAY.minusDays(10));
        Account closing = card("Closing", TODAY.plusDays(1));
        accounts(closedToday, closedBefore, closing);
        for (Account a : List.of(closedToday, closedBefore, closing)) {
            statements(a, stmt(LocalDate.of(2026, 8, 16), LocalDate.of(2026, 9, 15), StatementVerdict.AUTO_INGEST));
        }

        List<ObligationItemDto> items = upcoming(3, Set.of("statement_expected"));

        assertEquals(List.of("Closing"), items.stream().map(ObligationItemDto::accountName).toList());
    }

    @Test
    void cardsWithoutUsableStatementsExpectNothing() {
        Account none = card("None", null);
        Account rejected = card("Rejected", null);
        Account undated = card("Undated", null);
        accounts(none, rejected, undated);
        statements(none);
        statements(rejected, stmt(LocalDate.of(2026, 8, 16), LocalDate.of(2026, 9, 15), StatementVerdict.REJECTED));
        statements(undated, stmt(null, LocalDate.of(2026, 9, 15), StatementVerdict.AUTO_INGEST),
                stmt(LocalDate.of(2026, 9, 16), null, StatementVerdict.AUTO_INGEST));

        assertTrue(upcoming(3, Set.of("statement_expected")).isEmpty());
    }

    @Test
    void theLatestUsableStatementDrivesTheExpectedDate() {
        Account card = card("Mixed", null);
        accounts(card);
        statements(card,
                stmt(LocalDate.of(2026, 9, 16), LocalDate.of(2026, 10, 15), StatementVerdict.REJECTED),   // ignored
                stmt(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 9, 1), StatementVerdict.AUTO_INGEST),  // end before start: ignored
                stmt(LocalDate.of(2026, 8, 16), LocalDate.of(2026, 9, 15), StatementVerdict.NEEDS_REVIEW),
                stmt(LocalDate.of(2026, 7, 16), LocalDate.of(2026, 8, 15), StatementVerdict.AUTO_INGEST));

        ObligationItemDto i = upcoming(3, Set.of("statement_expected")).get(0);

        assertEquals(LocalDate.of(2026, 10, 15), i.date());
    }

    @Test
    void expectedStatementBeyondTheWindowIsDropped() {
        Account card = card("Future", null);
        accounts(card);
        // a statement closing 2026-10-20 puts the next one at 2026-11-20, past a one-month window (2026-11-08)
        statements(card, stmt(LocalDate.of(2026, 9, 21), LocalDate.of(2026, 10, 20), StatementVerdict.AUTO_INGEST));

        assertTrue(upcoming(1, Set.of("statement_expected")).isEmpty());
        assertEquals(LocalDate.of(2026, 11, 20), upcoming(2, Set.of("statement_expected")).get(0).date());
    }

    // ------------------------------------------------------------------ user / kinds / window

    @Test
    void withoutAUserOnlyEmisAndLendingsAreComputed() {
        UUID loanId = UUID.randomUUID();
        loans(loan(loanId, "L"));
        when(loanService.getLoanSchedule(loanId)).thenReturn(List.of(inst(1, TODAY.plusDays(1), "1", "upcoming")));
        when(lendingService.getUpcomingLendingObligations(TODAY, MAX_DATE)).thenReturn(List.of(
                rawLending(TODAY.plusDays(1), "1", null, UUID.randomUUID(), "P", LendingDirection.lent)));

        List<ObligationItemDto> items = service.upcoming(null, 3, null).items();

        assertEquals(Set.of("emi", "lending_due"), Set.copyOf(items.stream().map(ObligationItemDto::type).toList()));
        verifyNoInteractions(cardBillService, accountRepository, statementRepository);
    }

    @Test
    void nullOrEmptyKindsMeanEveryKind() {
        stubOneOfEachKind();
        assertEquals(Set.of("emi", "lending_due", "card_bill", "statement_expected"), typesOf(service.upcoming(userId, 3, null).items()));
        assertEquals(Set.of("emi", "lending_due", "card_bill", "statement_expected"), typesOf(service.upcoming(userId, 3, Set.of()).items()));
    }

    @Test
    void kindsLimitWhichSectionsAreComputed() {
        assertTrue(service.upcoming(userId, 3, Set.of("card_bill")).items().isEmpty());
        verify(cardBillService).listBills(userId);
        verifyNoInteractions(loanService, lendingService, accountRepository, statementRepository);
    }

    @Test
    void kindsCanCombine() {
        stubOneOfEachKind();
        assertEquals(Set.of("emi", "statement_expected"),
                typesOf(service.upcoming(userId, 3, Set.of("emi", "statement_expected")).items()));
    }

    @Test
    void monthsBelowOneUseAOneMonthWindow() {
        UUID loanId = UUID.randomUUID();
        loans(loan(loanId, "L"));
        when(loanService.getLoanSchedule(loanId)).thenReturn(List.of(
                inst(1, TODAY.plusMonths(1), "1", "upcoming"),
                inst(2, TODAY.plusMonths(1).plusDays(1), "1", "upcoming")));

        assertEquals(List.of(1), seqs(service.upcoming(userId, 0, Set.of("emi")).items()));
        assertEquals(List.of(1), seqs(service.upcoming(userId, -4, Set.of("emi")).items()));
    }

    @Test
    void monthsAboveTwelveUseATwelveMonthWindow() {
        UUID loanId = UUID.randomUUID();
        loans(loan(loanId, "L"));
        when(loanService.getLoanSchedule(loanId)).thenReturn(List.of(
                inst(1, TODAY.plusMonths(12), "1", "upcoming"),
                inst(2, TODAY.plusMonths(12).plusDays(1), "1", "upcoming")));

        assertEquals(List.of(1), seqs(service.upcoming(userId, 99, Set.of("emi")).items()));
    }

    @Test
    void theLendingWindowFollowsTheClampedMonths() {
        service.upcoming(userId, 6, Set.of("lending_due"));
        verify(lendingService).getUpcomingLendingObligations(TODAY, TODAY.plusMonths(6));
    }

    @Test
    void clampMonthsKeepsOneToTwelve() {
        assertEquals(1, ObligationsService.clampMonths(Integer.MIN_VALUE));
        assertEquals(1, ObligationsService.clampMonths(0));
        assertEquals(1, ObligationsService.clampMonths(1));
        assertEquals(6, ObligationsService.clampMonths(6));
        assertEquals(12, ObligationsService.clampMonths(12));
        assertEquals(12, ObligationsService.clampMonths(13));
    }

    // ------------------------------------------------------------------ parseKinds

    @Test
    void blankKindsParseToEveryKind() {
        assertSame(ObligationsService.ALL_KINDS, ObligationsService.parseKinds(null));
        assertSame(ObligationsService.ALL_KINDS, ObligationsService.parseKinds(""));
        assertSame(ObligationsService.ALL_KINDS, ObligationsService.parseKinds("   "));
        assertSame(ObligationsService.ALL_KINDS, ObligationsService.parseKinds(" , ,"));
    }

    @Test
    void kindsParseTrimmedCaseInsensitiveAndDeduplicatedInOrder() {
        assertEquals(List.of("card_bill", "emi"),
                List.copyOf(ObligationsService.parseKinds(" CARD_BILL , emi,,Emi ")));
        assertEquals(Set.of("lending_due", "statement_expected"),
                ObligationsService.parseKinds("lending_due,statement_expected"));
    }

    @Test
    void anUnknownKindIsAValidationError() {
        ValidationException ex = assertThrows(ValidationException.class,
                () -> ObligationsService.parseKinds("emi, lending_return "));
        assertEquals("Unknown obligation kind: lending_return", ex.getMessage());
    }

    // ------------------------------------------------------------------ order

    @Test
    void overdueFirstOldestFirstThenByDateUndatedLastTiesByTypeThenTitle() {
        UUID loanId = UUID.randomUUID();
        loans(loan(loanId, "Zeta"));
        when(loanService.getLoanSchedule(loanId)).thenReturn(List.of(
                inst(1, TODAY.minusDays(1), "1", "overdue"),
                inst(2, TODAY.plusDays(5), "1", "upcoming")));
        when(lendingService.getUpcomingLendingObligations(TODAY, MAX_DATE)).thenReturn(List.of(
                rawLending(TODAY.minusDays(9), "1", null, UUID.randomUUID(), "Old", LendingDirection.lent)));
        when(cardBillService.listBills(userId)).thenReturn(List.of(
                bill(UUID.randomUUID(), "undated", "1", UUID.randomUUID(), BillStatus.DUE_UNKNOWN, null, null, null),
                bill(UUID.randomUUID(), "beta", "1", UUID.randomUUID(), BillStatus.OPEN, TODAY.plusDays(5), "1", null),
                bill(UUID.randomUUID(), "Alpha", "1", UUID.randomUUID(), BillStatus.OPEN, TODAY.plusDays(5), "1", null)));
        Account card = card("Stmt", null);
        accounts(card);
        // expected 2026-10-04: four days late, inside the grace, so not overdue
        statements(card, stmt(LocalDate.of(2026, 8, 5), LocalDate.of(2026, 9, 4), StatementVerdict.AUTO_INGEST));

        List<String> titles = service.upcoming(userId, 3, null).items().stream().map(ObligationItemDto::title).toList();

        assertEquals(List.of(
                "Old owes you",            // overdue, oldest
                "Zeta EMI #1",             // overdue, newer
                "Stmt statement expected", // late but in grace: dated by its (past) date
                "Alpha ••1 bill",          // +5 card_bill, title case-insensitive
                "beta ••1 bill",           // +5 card_bill
                "Zeta EMI #2",             // +5 emi (type sorts after card_bill)
                "undated ••1 bill"         // no date: last
        ), titles);
    }

    // ------------------------------------------------------------------ failure isolation

    @Test
    void aFailingLoanListDropsOnlyTheEmiSection() {
        stubOneOfEachKind();
        when(loanService.getLoans(eq(LoanStatus.active), any())).thenThrow(new IllegalStateException("boom"));
        assertEquals(Set.of("lending_due", "card_bill", "statement_expected"), typesOf(service.upcoming(userId, 3, null).items()));
    }

    @Test
    void aFailingLoanScheduleDropsOnlyTheEmiSection() {
        UUID loanId = stubOneOfEachKind();
        when(loanService.getLoanSchedule(loanId)).thenThrow(new IllegalStateException("bad schedule"));
        assertEquals(Set.of("lending_due", "card_bill", "statement_expected"), typesOf(service.upcoming(userId, 3, null).items()));
    }

    @Test
    void aFailingLendingServiceDropsOnlyTheLendingSection() {
        stubOneOfEachKind();
        when(lendingService.getUpcomingLendingObligations(any(), any())).thenThrow(new IllegalStateException("boom"));
        assertEquals(Set.of("emi", "card_bill", "statement_expected"), typesOf(service.upcoming(userId, 3, null).items()));
    }

    @Test
    void aFailingBillServiceDropsOnlyTheCardBillSection() {
        stubOneOfEachKind();
        when(cardBillService.listBills(userId)).thenThrow(new IllegalStateException("boom"));
        assertEquals(Set.of("emi", "lending_due", "statement_expected"), typesOf(service.upcoming(userId, 3, null).items()));
    }

    @Test
    void aFailingStatementLookupDropsOnlyTheStatementSection() {
        stubOneOfEachKind();
        when(statementRepository.findByAccountIdOrderByPeriodEndDescNullsLast(any())).thenThrow(new IllegalStateException("boom"));
        assertEquals(Set.of("emi", "lending_due", "card_bill"), typesOf(service.upcoming(userId, 3, null).items()));
    }

    @Test
    void aFailingAccountLookupDropsOnlyTheStatementSection() {
        stubOneOfEachKind();
        when(accountRepository.findByUserIdAndType(userId, AccountType.credit_card)).thenThrow(new IllegalStateException("boom"));
        assertEquals(Set.of("emi", "lending_due", "card_bill"), typesOf(service.upcoming(userId, 3, null).items()));
    }

    // ------------------------------------------------------------------ status / daysUntil

    @Test
    void statusThresholds() {
        assertEquals("upcoming", ObligationsService.status(TODAY, null, 0));
        assertEquals("overdue", ObligationsService.status(TODAY, TODAY.minusDays(1), 0));
        assertEquals("due_soon", ObligationsService.status(TODAY, TODAY, 0));
        assertEquals("due_soon", ObligationsService.status(TODAY, TODAY.plusDays(7), 0));
        assertEquals("upcoming", ObligationsService.status(TODAY, TODAY.plusDays(8), 0));
        assertEquals("due_soon", ObligationsService.status(TODAY, TODAY.minusDays(5), 5));
        assertEquals("overdue", ObligationsService.status(TODAY, TODAY.minusDays(6), 5));
    }

    @Test
    void daysUntilIsSignedAndNullForUnknownDates() {
        assertNull(ObligationsService.daysUntil(TODAY, null));
        assertEquals(0L, ObligationsService.daysUntil(TODAY, TODAY));
        assertEquals(-3L, ObligationsService.daysUntil(TODAY, TODAY.minusDays(3)));
        assertEquals(31L, ObligationsService.daysUntil(TODAY, TODAY.plusDays(31)));
    }

    // ------------------------------------------------------------------ helpers

    private List<ObligationItemDto> upcoming(int months, Set<String> kinds) {
        return service.upcoming(userId, months, kinds).items();
    }

    /** One row of every kind; returns the loan id. */
    private UUID stubOneOfEachKind() {
        UUID loanId = UUID.randomUUID();
        loans(loan(loanId, "L"));
        when(loanService.getLoanSchedule(loanId)).thenReturn(List.of(inst(1, TODAY.plusDays(3), "1", "upcoming")));
        when(lendingService.getUpcomingLendingObligations(TODAY, MAX_DATE)).thenReturn(List.of(
                rawLending(TODAY.plusDays(3), "1", null, UUID.randomUUID(), "P", LendingDirection.lent)));
        when(cardBillService.listBills(userId)).thenReturn(List.of(
                bill(UUID.randomUUID(), "C", "1", UUID.randomUUID(), BillStatus.OPEN, TODAY.plusDays(3), "1", null)));
        Account card = card("S", null);
        accounts(card);
        statements(card, stmt(LocalDate.of(2026, 8, 16), LocalDate.of(2026, 9, 15), StatementVerdict.AUTO_INGEST));
        return loanId;
    }

    private static Set<String> typesOf(List<ObligationItemDto> items) {
        return Set.copyOf(items.stream().map(ObligationItemDto::type).toList());
    }

    private static List<Integer> seqs(List<ObligationItemDto> items) {
        return items.stream().map(ObligationItemDto::installmentSeq).toList();
    }

    private void loans(LoanResponse... loans) {
        when(loanService.getLoans(eq(LoanStatus.active), any())).thenReturn(new PageImpl<>(List.of(loans)));
    }

    private void accounts(Account... accounts) {
        when(accountRepository.findByUserIdAndType(userId, AccountType.credit_card)).thenReturn(List.of(accounts));
    }

    private void statements(Account account, Statement... statements) {
        when(statementRepository.findByAccountIdOrderByPeriodEndDescNullsLast(account.getId())).thenReturn(List.of(statements));
    }

    static LoanResponse loan(UUID id, String name) {
        return new LoanResponse(id, name, null, null, null, null, null, null, null, null, null, null, null,
                LoanStatus.active, null, null, null, null, null, null, null, null, null, null, null, null, null,
                false, null, null);
    }

    private static InstallmentDto inst(Integer seq, LocalDate due, String emi, String status) {
        return new InstallmentDto(seq, due, null, new BigDecimal(emi), null, null, null, status, null);
    }

    private static ObligationItemDto rawLending(LocalDate date, String amount, UUID lendingId, UUID counterpartyId,
                                                String name, LendingDirection direction) {
        return new ObligationItemDto("lending_due", date, new BigDecimal(amount), "upcoming",
                null, null, null, lendingId, counterpartyId, name, direction);
    }

    private static CardBill bill(UUID accountId, String name, String last4, UUID statementId, BillStatus status,
                                 LocalDate due, String total, String remaining) {
        return new CardBill(accountId, name, last4, statementId, null, null, due,
                total == null ? null : new BigDecimal(total), null, null,
                remaining == null ? null : new BigDecimal(remaining), PaidSource.NONE, status, null, null,
                List.of(), false, null, null, null, null, null, null);
    }

    private static Account card(String name, LocalDate closedOn) {
        Account a = new Account(name, AccountType.credit_card);
        a.setId(UUID.randomUUID());
        a.setClosedOn(closedOn);
        return a;
    }

    private static Statement stmt(LocalDate start, LocalDate end, StatementVerdict verdict) {
        Statement s = new Statement();
        s.setPeriodStart(start);
        s.setPeriodEnd(end);
        s.setVerdict(verdict);
        return s;
    }
}
