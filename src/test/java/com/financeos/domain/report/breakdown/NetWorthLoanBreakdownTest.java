package com.financeos.domain.report.breakdown;

import static com.financeos.domain.report.breakdown.BreakdownAssertions.assertReconciles;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.financeos.api.loan.dto.InstallmentDto;
import com.financeos.api.loan.dto.LoanEventResponse;
import com.financeos.api.loan.dto.LoanResponse;
import com.financeos.core.exception.ResourceNotFoundException;
import com.financeos.core.time.AppTime;
import com.financeos.domain.account.AccountService;
import com.financeos.domain.lending.LendingService;
import com.financeos.domain.loan.Loan;
import com.financeos.domain.loan.LoanEvent;
import com.financeos.domain.loan.LoanEventType;
import com.financeos.domain.loan.LoanService;
import com.financeos.domain.loan.LoanService.LoanScheduleDetail;
import com.financeos.domain.loan.LoanStatus;
import com.financeos.domain.loan.LoanType;
import com.financeos.domain.loan.RateType;
import com.financeos.domain.loan.schedule.LoanScheduleService;
import com.financeos.domain.loan.schedule.ScheduleResult;
import com.financeos.domain.report.datasource.impl.NetWorthDatasource;
import com.financeos.domain.report.engine.TableData;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;

/**
 * Loan rows of the net worth breakdown, over schedules computed by the real
 * {@link LoanScheduleService} as of a fixed today; each breakdown is checked to reconcile exactly
 * to the outstanding principal the {@code net_worth} datasource lists.
 */
class NetWorthLoanBreakdownTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 8);

    private LoanService loanService;
    private NetWorthLoanBreakdown breakdown;

    @BeforeEach
    void setUp() {
        AppTime.useClock(Clock.fixed(TODAY.atTime(10, 0).atZone(IST).toInstant(), IST));
        loanService = mock(LoanService.class);
        breakdown = new NetWorthLoanBreakdown(loanService);
    }

    @AfterEach
    void tearDown() {
        AppTime.reset();
    }

    @Test
    void outstandingIsThePrincipalLessThePrincipalOfEveryEmiDueSoFar() {
        Loan loan = loan("100000", LocalDate.of(2026, 1, 5));
        LoanScheduleDetail detail = schedule(loan);

        RowBreakdownResponse r = breakdown.breakdown(loan.getId(), 25).orElseThrow();

        List<InstallmentDto> due = due(detail);
        assertEquals(10, due.size());
        assertEquals(List.of(
                step("start", "Loan principal", new BigDecimal("100000")),
                step("subtract", "Principal repaid through 10 EMIs due so far", principalSum(due)),
                step("equals", "Outstanding after EMI #10 (05/10/2026)", due.get(9).closingBalance())), r.steps());
        assertEquals("net_worth", r.datasource());
        assertEquals(loan.getId().toString(), r.rowId());
        assertEquals("Car", r.title());
        assertEquals("Liability", r.subtitle());
        assertEquals("Loan", r.kindLabel());
        assertEquals(due.get(9).closingBalance(), r.total());
        assertEquals("Outstanding after EMI #10 (05/10/2026)", r.totalLabel());
        assertEquals(TODAY, r.asOf());
        assertEquals(List.of(), r.notes());
        assertEquals(List.of("installments"), r.sections().stream().map(BreakdownSectionData::key).toList());
        BreakdownSectionData installments = r.sections().get(0);
        assertEquals("EMIs due so far", installments.label());
        assertEquals(null, installments.rowAction());
        TableData table = (TableData) installments.table();
        assertEquals(List.of("seq", "dueDate", "emi", "interest", "principal", "closingBalance"),
                table.columns().stream().map(TableData.Column::key).toList());
        assertEquals(installmentRow(due.get(9)), table.rows().get(0));
        assertEquals(installmentRow(due.get(0)), table.rows().get(9));
        assertEquals(new TableData.Page(0, 25, 10, 1), table.page());
        assertMatchesListedRow(detail.loan(), r);
    }

    @Test
    void prepaymentsCountOnceTheNextEmiIsDueAndLaterOnesAreNoted() {
        Loan loan = loan("100000", LocalDate.of(2026, 1, 5));
        LoanEvent applied = event(loan, LoanEventType.prepayment, LocalDate.of(2026, 3, 20), "10000");
        LoanEvent afterLastEmi = event(loan, LoanEventType.prepayment, LocalDate.of(2026, 10, 6), "5000");
        LoanEvent future = event(loan, LoanEventType.prepayment, LocalDate.of(2026, 12, 1), "1000");
        LoanScheduleDetail detail = schedule(loan, applied, afterLastEmi, future);

        RowBreakdownResponse r = breakdown.breakdown(loan.getId(), 25).orElseThrow();

        List<InstallmentDto> due = due(detail);
        assertEquals(List.of(
                step("start", "Loan principal", new BigDecimal("100000")),
                step("subtract", "Principal repaid through " + due.size() + " EMIs due so far", principalSum(due)),
                step("subtract", "Prepayments (1)", new BigDecimal("10000")),
                step("equals", "Outstanding after EMI #" + due.size() + " (05/10/2026)", due.get(due.size() - 1).closingBalance())),
                r.steps());
        assertEquals(List.of("Prepayments made after the last EMI due so far count from the next EMI."), r.notes());
        assertEquals(List.of("installments", "prepayments"), r.sections().stream().map(BreakdownSectionData::key).toList());
        TableData prepayments = (TableData) r.sections().get(1).table();
        assertEquals(List.of("date", "amount", "counted"), prepayments.columns().stream().map(TableData.Column::key).toList());
        assertEquals(List.of(
                prepaymentRow(future, false), prepaymentRow(afterLastEmi, false), prepaymentRow(applied, true)),
                prepayments.rows());
        assertMatchesListedRow(detail.loan(), r);
    }

    @Test
    void beforeTheFirstEmiTheOutstandingIsThePrincipal() {
        Loan loan = loan("50000", LocalDate.of(2026, 11, 1));
        LoanScheduleDetail detail = schedule(loan, event(loan, LoanEventType.prepayment, LocalDate.of(2026, 10, 1), "2000"));

        RowBreakdownResponse r = breakdown.breakdown(loan.getId(), 25).orElseThrow();

        assertEquals(List.of(
                step("start", "Loan principal", new BigDecimal("50000")),
                step("equals", "Outstanding", new BigDecimal("50000.00"))), r.steps());
        assertEquals(List.of("Prepayments made after the last EMI due so far count from the next EMI."), r.notes());
        assertEquals(new TableData.Page(0, 25, 0, 1), ((TableData) r.sections().get(0).table()).page());
        assertMatchesListedRow(detail.loan(), r);
    }

    @Test
    void foreclosedLoanClearsWhatWasLeftAndEndsAtZero() {
        Loan loan = loan("100000", LocalDate.of(2026, 1, 5));
        LoanEvent reflected = event(loan, LoanEventType.prepayment, LocalDate.of(2026, 3, 20), "10000");
        LoanEvent beforeForeclosure = event(loan, LoanEventType.prepayment, LocalDate.of(2026, 6, 8), "8000");
        LoanEvent foreclosure = event(loan, LoanEventType.foreclosure, LocalDate.of(2026, 6, 10), "60000");
        LoanScheduleDetail detail = schedule(loan, reflected, beforeForeclosure, foreclosure);

        RowBreakdownResponse r = breakdown.breakdown(loan.getId(), 25).orElseThrow();

        List<InstallmentDto> due = due(detail);
        assertEquals(6, due.size());
        BigDecimal cleared = due.get(5).closingBalance().subtract(new BigDecimal("8000"));
        assertEquals(List.of(
                step("start", "Loan principal", new BigDecimal("100000")),
                step("subtract", "Principal repaid through 6 EMIs due so far", principalSum(due)),
                step("subtract", "Prepayments (2)", new BigDecimal("18000")),
                step("subtract", "Principal cleared at foreclosure", cleared),
                new BreakdownStep("info", "Foreclosed on 10/06/2026", null, new BigDecimal("60000"), "currency"),
                step("equals", "Outstanding", new BigDecimal("0.00"))), r.steps());
        assertEquals("Outstanding", r.totalLabel());
        assertEquals(List.of(), r.notes());
        TableData prepayments = (TableData) r.sections().get(1).table();
        assertEquals(List.of(prepaymentRow(beforeForeclosure, true), prepaymentRow(reflected, true)), prepayments.rows());
        assertMatchesListedRow(detail.loan(), r);
    }

    @Test
    void foreclosureBeforeAnyEmiClearsThePrincipalLessPrepayments() {
        Loan loan = loan("40000", LocalDate.of(2026, 11, 1));
        LoanEvent prepayment = event(loan, LoanEventType.prepayment, LocalDate.of(2026, 9, 1), "1000");
        LoanEvent foreclosure = event(loan, LoanEventType.foreclosure, LocalDate.of(2026, 10, 1), "39500");
        LoanScheduleDetail detail = schedule(loan, prepayment, foreclosure);

        RowBreakdownResponse r = breakdown.breakdown(loan.getId(), 25).orElseThrow();

        assertEquals(List.of(
                step("start", "Loan principal", new BigDecimal("40000")),
                step("subtract", "Prepayments (1)", new BigDecimal("1000")),
                step("subtract", "Principal cleared at foreclosure", new BigDecimal("39000")),
                new BreakdownStep("info", "Foreclosed on 01/10/2026", null, new BigDecimal("39500"), "currency"),
                step("equals", "Outstanding", new BigDecimal("0.00"))), r.steps());
        assertMatchesListedRow(detail.loan(), r);
    }

    @Test
    void foreclosureDatedAfterTodayHasNotHappenedYet() {
        Loan loan = loan("100000", LocalDate.of(2026, 1, 5));
        LoanScheduleDetail detail = schedule(loan,
                event(loan, LoanEventType.foreclosure, LocalDate.of(2026, 12, 20), "1000"));

        RowBreakdownResponse r = breakdown.breakdown(loan.getId(), 25).orElseThrow();

        assertEquals("Outstanding after EMI #10 (05/10/2026)", r.totalLabel());
        assertTrue(r.steps().stream().noneMatch(s -> s.label().startsWith("Principal cleared")));
        assertMatchesListedRow(detail.loan(), r);
    }

    @Test
    void principalWithSubPaisaDigitsShowsTheScheduleRoundingExplicitly() {
        Loan loan = loan("100000.005", LocalDate.of(2026, 9, 5));
        loan.setEmiAmount(new BigDecimal("10000"));
        LoanScheduleDetail detail = schedule(loan);

        RowBreakdownResponse r = breakdown.breakdown(loan.getId(), 25).orElseThrow();

        assertEquals(step("add", "Rounding difference", new BigDecimal("0.005")), r.steps().get(2));
        assertReconciles(r);
        assertMatchesListedRow(detail.loan(), r);
    }

    @Test
    void inactiveOrUnknownLoansHaveNoBreakdown() {
        Loan closed = loan("1000", LocalDate.of(2026, 1, 5));
        closed.setStatus(LoanStatus.closed);
        schedule(closed);
        Loan foreclosed = loan("1000", LocalDate.of(2026, 1, 5));
        foreclosed.setStatus(LoanStatus.foreclosed);
        schedule(foreclosed);
        UUID unknown = UUID.randomUUID();
        when(loanService.findOwnedLoanSchedule(unknown)).thenReturn(Optional.empty());

        assertTrue(breakdown.breakdown(closed.getId(), 25).isEmpty());
        assertTrue(breakdown.breakdown(foreclosed.getId(), 25).isEmpty());
        assertTrue(breakdown.breakdown(unknown, 25).isEmpty());
        assertTrue(breakdown.section(closed.getId(), "installments", 0, 25).isEmpty());
        assertTrue(breakdown.section(unknown, "installments", 0, 25).isEmpty());
    }

    @Test
    void installmentsSectionPagesNewestFirst() {
        Loan loan = loan("100000", LocalDate.of(2026, 1, 5));
        LoanScheduleDetail detail = schedule(loan);

        TableData table = (TableData) breakdown.section(loan.getId(), "installments", 1, 3).orElseThrow();

        List<InstallmentDto> due = due(detail);
        assertEquals(List.of(installmentRow(due.get(6)), installmentRow(due.get(5)), installmentRow(due.get(4))), table.rows());
        assertEquals(new TableData.Page(1, 3, 10, 4), table.page());
    }

    @Test
    void prepaymentsSectionPagesNewestFirst() {
        Loan loan = loan("100000", LocalDate.of(2026, 1, 5));
        LoanEvent first = event(loan, LoanEventType.prepayment, LocalDate.of(2026, 2, 10), "1000");
        LoanEvent second = event(loan, LoanEventType.prepayment, LocalDate.of(2026, 4, 10), "2000");
        schedule(loan, first, second);

        TableData table = (TableData) breakdown.section(loan.getId(), "prepayments", 1, 1).orElseThrow();

        assertEquals(List.of(prepaymentRow(first, true)), table.rows());
        assertEquals(new TableData.Page(1, 1, 2, 2), table.page());
    }

    @Test
    void prepaymentsOnTheSameDayAreOrderedByCreationNewestFirst() {
        Loan loan = loan("100000", LocalDate.of(2026, 1, 5));
        LoanEvent older = event(loan, LoanEventType.prepayment, LocalDate.of(2026, 2, 10), "1000");
        older.setCreatedAt(Instant.parse("2026-02-10T05:00:00Z"));
        LoanEvent newer = event(loan, LoanEventType.prepayment, LocalDate.of(2026, 2, 10), "500");
        newer.setCreatedAt(Instant.parse("2026-02-10T06:00:00Z"));
        schedule(loan, older, newer);

        TableData table = (TableData) breakdown.section(loan.getId(), "prepayments", 0, 25).orElseThrow();

        assertEquals(List.of(prepaymentRow(newer, true), prepaymentRow(older, true)), table.rows());
    }

    @Test
    void prepaymentsSectionWithoutPrepaymentsAndUnknownSectionsAreNotFound() {
        Loan loan = loan("100000", LocalDate.of(2026, 1, 5));
        schedule(loan, event(loan, LoanEventType.rate_change, LocalDate.of(2026, 2, 1), null));

        assertThrows(ResourceNotFoundException.class, () -> breakdown.section(loan.getId(), "prepayments", 0, 25));
        assertThrows(ResourceNotFoundException.class, () -> breakdown.section(loan.getId(), "nope", 0, 25));
    }

    // ------------------------------------------------------------------ day boundaries
    // The schedule folds an event into the first EMI due strictly after it, counts an EMI due today
    // and applies a foreclosure dated today; the expected EMI counts below are literal on purpose.

    @Test
    void prepaymentOnTheLastDueEmisDateIsNotCountedYet() {
        Loan loan = loan("100000", LocalDate.of(2026, 1, 5));
        LoanEvent onLastEmi = event(loan, LoanEventType.prepayment, LocalDate.of(2026, 10, 5), "5000");
        LoanScheduleDetail detail = schedule(loan, onLastEmi);

        RowBreakdownResponse r = breakdown.breakdown(loan.getId(), 25).orElseThrow();

        List<InstallmentDto> due = detail.installments().subList(0, 10);
        assertEquals(LocalDate.of(2026, 10, 5), due.get(9).dueDate());
        assertEquals(List.of(
                step("start", "Loan principal", new BigDecimal("100000")),
                step("subtract", "Principal repaid through 10 EMIs due so far", principalSum(due)),
                step("equals", "Outstanding after EMI #10 (05/10/2026)", due.get(9).closingBalance())), r.steps());
        assertEquals(List.of("Prepayments made after the last EMI due so far count from the next EMI."), r.notes());
        assertEquals(List.of(prepaymentRow(onLastEmi, false)), ((TableData) r.sections().get(1).table()).rows());
        assertMatchesListedRow(detail.loan(), r);
    }

    @Test
    void prepaymentOnAnEarlierEmisDateIsCounted() {
        Loan loan = loan("100000", LocalDate.of(2026, 1, 5));
        LoanEvent onEarlierEmi = event(loan, LoanEventType.prepayment, LocalDate.of(2026, 3, 5), "10000");
        LoanScheduleDetail detail = schedule(loan, onEarlierEmi);

        RowBreakdownResponse r = breakdown.breakdown(loan.getId(), 25).orElseThrow();

        List<InstallmentDto> due = detail.installments().subList(0, 10);
        assertEquals(LocalDate.of(2026, 10, 5), due.get(9).dueDate());
        assertEquals(List.of(
                step("start", "Loan principal", new BigDecimal("100000")),
                step("subtract", "Principal repaid through 10 EMIs due so far", principalSum(due)),
                step("subtract", "Prepayments (1)", new BigDecimal("10000")),
                step("equals", "Outstanding after EMI #10 (05/10/2026)", due.get(9).closingBalance())), r.steps());
        assertEquals(List.of(), r.notes());
        assertEquals(List.of(prepaymentRow(onEarlierEmi, true)), ((TableData) r.sections().get(1).table()).rows());
        assertMatchesListedRow(detail.loan(), r);
    }

    @Test
    void emiDueTodayIsAmongTheEmisDueSoFar() {
        Loan loan = loan("100000", LocalDate.of(2026, 1, 8));
        LoanScheduleDetail detail = schedule(loan);

        RowBreakdownResponse r = breakdown.breakdown(loan.getId(), 25).orElseThrow();

        List<InstallmentDto> due = detail.installments().subList(0, 10);
        assertEquals(TODAY, due.get(9).dueDate());
        assertEquals(List.of(
                step("start", "Loan principal", new BigDecimal("100000")),
                step("subtract", "Principal repaid through 10 EMIs due so far", principalSum(due)),
                step("equals", "Outstanding after EMI #10 (08/10/2026)", due.get(9).closingBalance())), r.steps());
        assertEquals("Outstanding after EMI #10 (08/10/2026)", r.totalLabel());
        TableData installments = (TableData) r.sections().get(0).table();
        assertEquals(installmentRow(due.get(9)), installments.rows().get(0));
        assertEquals(10, installments.page().totalElements());
        assertMatchesListedRow(detail.loan(), r);
    }

    @Test
    void foreclosureDatedTodayHasTakenEffect() {
        Loan loan = loan("100000", LocalDate.of(2026, 1, 5));
        LoanScheduleDetail detail = schedule(loan, event(loan, LoanEventType.foreclosure, TODAY, "60000"));

        RowBreakdownResponse r = breakdown.breakdown(loan.getId(), 25).orElseThrow();

        List<InstallmentDto> due = detail.installments().subList(0, 10);
        assertEquals(10, detail.installments().size());
        assertEquals(List.of(
                step("start", "Loan principal", new BigDecimal("100000")),
                step("subtract", "Principal repaid through 10 EMIs due so far", principalSum(due)),
                step("subtract", "Principal cleared at foreclosure", due.get(9).closingBalance()),
                new BreakdownStep("info", "Foreclosed on 08/10/2026", null, new BigDecimal("60000"), "currency"),
                step("equals", "Outstanding", new BigDecimal("0.00"))), r.steps());
        assertEquals("Outstanding", r.totalLabel());
        assertEquals(List.of(), r.notes());
        assertMatchesListedRow(detail.loan(), r);
    }

    @Test
    void prepaymentOnTheLastDueEmisDateIsClearedByALaterForeclosure() {
        Loan loan = loan("100000", LocalDate.of(2026, 1, 5));
        LoanEvent onLastEmi = event(loan, LoanEventType.prepayment, LocalDate.of(2026, 10, 5), "5000");
        LoanScheduleDetail detail = schedule(loan, onLastEmi,
                event(loan, LoanEventType.foreclosure, TODAY, "55000"));

        RowBreakdownResponse r = breakdown.breakdown(loan.getId(), 25).orElseThrow();

        List<InstallmentDto> due = detail.installments().subList(0, 10);
        assertEquals(10, detail.installments().size());
        assertEquals(List.of(
                step("start", "Loan principal", new BigDecimal("100000")),
                step("subtract", "Principal repaid through 10 EMIs due so far", principalSum(due)),
                step("subtract", "Prepayments (1)", new BigDecimal("5000")),
                step("subtract", "Principal cleared at foreclosure",
                        due.get(9).closingBalance().subtract(new BigDecimal("5000"))),
                new BreakdownStep("info", "Foreclosed on 08/10/2026", null, new BigDecimal("55000"), "currency"),
                step("equals", "Outstanding", new BigDecimal("0.00"))), r.steps());
        assertEquals(List.of(prepaymentRow(onLastEmi, true)), ((TableData) r.sections().get(1).table()).rows());
        assertMatchesListedRow(detail.loan(), r);
    }

    // ------------------------------------------------------------------ fixtures

    private void assertMatchesListedRow(LoanResponse loan, RowBreakdownResponse r) {
        AccountService accounts = mock(AccountService.class);
        when(accounts.getAllAccounts()).thenReturn(List.of());
        LoanService loans = mock(LoanService.class);
        when(loans.getLoans(eq(LoanStatus.active), any())).thenReturn(new PageImpl<>(List.of(loan)));
        LendingService lendings = mock(LendingService.class);
        when(lendings.getCounterparties(isNull(), any())).thenReturn(new PageImpl<>(List.of()));
        Map<String, Object> row = new NetWorthDatasource(accounts, loans, lendings).rows().get(0);

        assertEquals(row.get("value"), r.total());
        assertReconciles(r);
    }

    private static Loan loan(String principal, LocalDate firstEmi) {
        Loan loan = new Loan();
        loan.setId(UUID.randomUUID());
        loan.setName("Car");
        loan.setLoanType(LoanType.home);
        loan.setPrincipal(new BigDecimal(principal));
        loan.setAnnualRatePct(new BigDecimal("12"));
        loan.setRateType(RateType.fixed);
        loan.setTenureMonths(24);
        loan.setStartDate(firstEmi.minusMonths(1));
        loan.setFirstEmiDate(firstEmi);
        loan.setStatus(LoanStatus.active);
        return loan;
    }

    private static LoanEvent event(Loan loan, LoanEventType type, LocalDate date, String amount) {
        LoanEvent e = new LoanEvent();
        e.setId(UUID.randomUUID());
        e.setLoan(loan);
        e.setEventType(type);
        e.setEffectiveDate(date);
        e.setAmount(amount == null ? null : new BigDecimal(amount));
        if (type == LoanEventType.rate_change) {
            e.setNewAnnualRatePct(new BigDecimal("12"));
        }
        return e;
    }

    /** The detail LoanService returns: events in effective-date order, schedule computed as of today. */
    private LoanScheduleDetail schedule(Loan loan, LoanEvent... events) {
        List<LoanEvent> ordered = new ArrayList<>(List.of(events));
        ordered.sort((a, b) -> a.getEffectiveDate().compareTo(b.getEffectiveDate()));
        ScheduleResult result = new LoanScheduleService().compute(loan, ordered, List.of(), List.of());
        LoanScheduleDetail detail = new LoanScheduleDetail(LoanResponse.from(loan, result),
                ordered.stream().map(LoanEventResponse::from).toList(), result.installments());
        when(loanService.findOwnedLoanSchedule(loan.getId())).thenReturn(Optional.of(detail));
        return detail;
    }

    private static List<InstallmentDto> due(LoanScheduleDetail detail) {
        return detail.installments().stream().filter(i -> !i.dueDate().isAfter(TODAY)).toList();
    }

    private static BigDecimal principalSum(List<InstallmentDto> installments) {
        return installments.stream().map(InstallmentDto::principal).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static Map<String, Object> installmentRow(InstallmentDto i) {
        Map<String, Object> row = new java.util.LinkedHashMap<>();
        row.put("id", String.valueOf(i.seq()));
        row.put("seq", i.seq());
        row.put("dueDate", i.dueDate());
        row.put("emi", i.emi());
        row.put("interest", i.interest());
        row.put("principal", i.principal());
        row.put("closingBalance", i.closingBalance());
        return row;
    }

    private static Map<String, Object> prepaymentRow(LoanEvent e, boolean counted) {
        Map<String, Object> row = new java.util.LinkedHashMap<>();
        row.put("id", e.getId().toString());
        row.put("date", e.getEffectiveDate());
        row.put("amount", e.getAmount());
        row.put("counted", counted);
        return row;
    }

    private static BreakdownStep step(String op, String label, BigDecimal amount) {
        return new BreakdownStep(op, label, null, amount, "currency");
    }
}
