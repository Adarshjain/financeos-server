package com.financeos.domain.report.datasource.impl;

import com.financeos.domain.loan.*;
import com.financeos.domain.loan.schedule.LoanScheduleService;
import com.financeos.domain.loan.schedule.ScheduleResult;
import com.financeos.domain.report.engine.DateRangeResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LoanTaxSummaryDatasourceTest {

    private LoanService loanService;
    private LoanScheduleService scheduleService;
    private DateRangeResolver dateRangeResolver;
    private LoanTaxSummaryDatasource datasource;

    @BeforeEach
    void setUp() {
        loanService = mock(LoanService.class);
        scheduleService = new LoanScheduleService();
        dateRangeResolver = new DateRangeResolver(4);
        datasource = new LoanTaxSummaryDatasource(loanService, dateRangeResolver);
    }

    @Test
    void catalogShapeAndName() {
        assertEquals("loan_tax_summary", datasource.name());
        assertEquals("Loan Tax Summary", datasource.label());
        assertNotNull(datasource.fields());
        assertTrue(datasource.fields().stream().anyMatch(f -> "financialYear".equals(f.name())));
        assertTrue(datasource.fields().stream().anyMatch(f -> "sec24bInterest".equals(f.name())));
        assertTrue(datasource.fields().stream().anyMatch(f -> "sec80cPrincipal".equals(f.name())));
        assertTrue(datasource.fields().stream().anyMatch(f -> "sec80eInterest".equals(f.name())));
    }

    @Test
    void rowsBucketsPaymentsIntoIndianFYsAndAppliesEligibilityForHomeAndCarLoans() {
        UUID homeLoanId = UUID.randomUUID();
        Loan homeLoan = new Loan();
        homeLoan.setId(homeLoanId);
        homeLoan.setName("SBI Home Loan");
        homeLoan.setLoanType(LoanType.home);
        homeLoan.setLender("SBI");
        homeLoan.setPrincipal(new BigDecimal("1000000"));
        homeLoan.setAnnualRatePct(new BigDecimal("9.0"));
        homeLoan.setRateType(RateType.fixed);
        homeLoan.setTenureMonths(24);
        homeLoan.setStartDate(LocalDate.of(2026, 2, 1));
        homeLoan.setFirstEmiDate(LocalDate.of(2026, 3, 1));
        homeLoan.setStatus(LoanStatus.active);

        // Payment 1 in March 2026 -> FY 2025-26 ("FY 25-26")
        LoanPayment p1 = new LoanPayment();
        p1.setId(UUID.randomUUID());
        p1.setInstallmentSeq(1);
        p1.setPaymentDate(LocalDate.of(2026, 3, 10));
        p1.setAmount(new BigDecimal("45684.74"));

        // Payment 2 in April 2026 -> FY 2026-27 ("FY 26-27")
        LoanPayment p2 = new LoanPayment();
        p2.setId(UUID.randomUUID());
        p2.setInstallmentSeq(2);
        p2.setPaymentDate(LocalDate.of(2026, 4, 10));
        p2.setAmount(new BigDecimal("45684.74"));

        ScheduleResult homeSchedule = scheduleService.compute(homeLoan, List.of(), List.of(p1, p2), List.of(), LocalDate.of(2026, 2, 1));

        // Car loan with payment in March 2026 -> FY 2025-26 ("FY 25-26")
        UUID carLoanId = UUID.randomUUID();
        Loan carLoan = new Loan();
        carLoan.setId(carLoanId);
        carLoan.setName("ICICI Car Loan");
        carLoan.setLoanType(LoanType.car);
        carLoan.setLender("ICICI");
        carLoan.setPrincipal(new BigDecimal("500000"));
        carLoan.setAnnualRatePct(new BigDecimal("10.0"));
        carLoan.setRateType(RateType.fixed);
        carLoan.setTenureMonths(12);
        carLoan.setStartDate(LocalDate.of(2026, 2, 1));
        carLoan.setFirstEmiDate(LocalDate.of(2026, 3, 1));
        carLoan.setStatus(LoanStatus.active);

        LoanPayment pCar = new LoanPayment();
        pCar.setId(UUID.randomUUID());
        pCar.setInstallmentSeq(1);
        pCar.setPaymentDate(LocalDate.of(2026, 3, 15));
        pCar.setAmount(new BigDecimal("43957.94"));

        ScheduleResult carSchedule = scheduleService.compute(carLoan, List.of(), List.of(pCar), List.of(), LocalDate.of(2026, 2, 1));

        when(loanService.getAllLoansWithSchedule()).thenReturn(List.of(
                new LoanService.LoanWithSchedule(homeLoan, homeSchedule),
                new LoanService.LoanWithSchedule(carLoan, carSchedule)
        ));

        List<Map<String, Object>> rows = datasource.rows();
        // 2 rows for home loan (FY 25-26 and FY 26-27) + 1 row for car loan (FY 25-26)
        assertEquals(3, rows.size());

        // Row 1: Home loan FY 25-26
        Map<String, Object> r1 = rows.get(0);
        assertEquals(homeLoanId + "_FY 25-26", r1.get("id"));
        assertEquals("FY 25-26", r1.get("financialYear"));
        assertEquals(homeLoanId.toString(), r1.get("loanId"));
        assertEquals("SBI Home Loan", r1.get("loanName"));
        assertEquals("home", r1.get("loanType"));
        assertEquals(homeSchedule.installments().get(0).interest(), r1.get("interestPaid"));
        assertEquals(homeSchedule.installments().get(0).principal(), r1.get("principalPaid"));
        assertEquals(r1.get("interestPaid"), r1.get("sec24bInterest"));
        assertEquals(r1.get("principalPaid"), r1.get("sec80cPrincipal"));
        assertEquals(BigDecimal.ZERO, r1.get("sec80eInterest"));

        // Row 2: Home loan FY 26-27
        Map<String, Object> r2 = rows.get(1);
        assertEquals(homeLoanId + "_FY 26-27", r2.get("id"));
        assertEquals("FY 26-27", r2.get("financialYear"));
        assertEquals(homeSchedule.installments().get(1).interest(), r2.get("interestPaid"));
        assertEquals(homeSchedule.installments().get(1).principal(), r2.get("principalPaid"));
        assertEquals(r2.get("interestPaid"), r2.get("sec24bInterest"));
        assertEquals(r2.get("principalPaid"), r2.get("sec80cPrincipal"));
        assertEquals(BigDecimal.ZERO, r2.get("sec80eInterest"));

        // Row 3: Car loan FY 25-26 (zeroes all eligibility columns)
        Map<String, Object> r3 = rows.get(2);
        assertEquals(carLoanId + "_FY 25-26", r3.get("id"));
        assertEquals("FY 25-26", r3.get("financialYear"));
        assertEquals("car", r3.get("loanType"));
        assertEquals(carSchedule.installments().get(0).interest(), r3.get("interestPaid"));
        assertEquals(carSchedule.installments().get(0).principal(), r3.get("principalPaid"));
        assertEquals(BigDecimal.ZERO, r3.get("sec24bInterest"), "Car loan has no sec24b eligibility");
        assertEquals(BigDecimal.ZERO, r3.get("sec80cPrincipal"), "Car loan has no sec80c eligibility");
        assertEquals(BigDecimal.ZERO, r3.get("sec80eInterest"), "Car loan has no sec80e eligibility");
    }

    @Test
    void rowsCalculatesSec80eForEducationLoan() {
        UUID eduLoanId = UUID.randomUUID();
        Loan eduLoan = new Loan();
        eduLoan.setId(eduLoanId);
        eduLoan.setName("Vidya Loan");
        eduLoan.setLoanType(LoanType.education);
        eduLoan.setLender("Canara Bank");
        eduLoan.setPrincipal(new BigDecimal("800000"));
        eduLoan.setAnnualRatePct(new BigDecimal("8.0"));
        eduLoan.setRateType(RateType.fixed);
        eduLoan.setTenureMonths(12);
        eduLoan.setStartDate(LocalDate.of(2025, 4, 1));
        eduLoan.setFirstEmiDate(LocalDate.of(2025, 5, 1));
        eduLoan.setStatus(LoanStatus.active);

        LoanPayment p = new LoanPayment();
        p.setId(UUID.randomUUID());
        p.setInstallmentSeq(1);
        p.setPaymentDate(LocalDate.of(2025, 5, 5));
        p.setAmount(new BigDecimal("69597.51"));

        ScheduleResult schedule = scheduleService.compute(eduLoan, List.of(), List.of(p), List.of(), LocalDate.of(2025, 4, 1));

        when(loanService.getAllLoansWithSchedule()).thenReturn(List.of(
                new LoanService.LoanWithSchedule(eduLoan, schedule)
        ));

        List<Map<String, Object>> rows = datasource.rows();
        assertEquals(1, rows.size());

        Map<String, Object> r = rows.get(0);
        assertEquals("education", r.get("loanType"));
        assertEquals(BigDecimal.ZERO, r.get("sec24bInterest"));
        assertEquals(BigDecimal.ZERO, r.get("sec80cPrincipal"));
        assertEquals(r.get("interestPaid"), r.get("sec80eInterest"));
    }
}
