package com.financeos.domain.report.datasource.impl;

import com.financeos.domain.loan.*;
import com.financeos.domain.loan.schedule.LoanScheduleService;
import com.financeos.domain.loan.schedule.ScheduleResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LoanPaymentsDatasourceTest {

    private LoanService loanService;
    private LoanScheduleService scheduleService;
    private LoanPaymentsDatasource datasource;

    @BeforeEach
    void setUp() {
        loanService = mock(LoanService.class);
        scheduleService = new LoanScheduleService();
        datasource = new LoanPaymentsDatasource(loanService);
    }

    @Test
    void catalogShapeAndName() {
        assertEquals("loan_payments", datasource.name());
        assertEquals("Loan Payments", datasource.label());
        assertNotNull(datasource.fields());
        assertTrue(datasource.fields().stream().anyMatch(f -> "paymentDate".equals(f.name())));
        assertTrue(datasource.fields().stream().anyMatch(f -> "interestComponent".equals(f.name())));
        assertTrue(datasource.fields().stream().anyMatch(f -> "principalComponent".equals(f.name())));
        assertTrue(datasource.fields().stream().anyMatch(f -> "loanType".equals(f.name())));
    }

    @Test
    void rowsEmitsOnlySettledInstallmentsWithScheduleDerivedSplit() {
        UUID loanId = UUID.randomUUID();
        Loan loan = new Loan();
        loan.setId(loanId);
        loan.setName("HDFC Home Loan");
        loan.setLoanType(LoanType.home);
        loan.setLender("HDFC");
        loan.setPrincipal(new BigDecimal("1200000"));
        loan.setAnnualRatePct(new BigDecimal("8.5"));
        loan.setRateType(RateType.fixed);
        loan.setTenureMonths(60);
        loan.setStartDate(LocalDate.of(2025, 1, 1));
        loan.setFirstEmiDate(LocalDate.of(2025, 2, 1));
        loan.setStatus(LoanStatus.active);

        UUID p1Id = UUID.randomUUID();
        LoanPayment p1 = new LoanPayment();
        p1.setId(p1Id);
        p1.setInstallmentSeq(1);
        p1.setPaymentDate(LocalDate.of(2025, 2, 1));
        p1.setAmount(new BigDecimal("25000.00")); // actual paid amount differs slightly from scheduled emi

        UUID p2Id = UUID.randomUUID();
        LoanPayment p2 = new LoanPayment();
        p2.setId(p2Id);
        p2.setInstallmentSeq(2);
        p2.setPaymentDate(LocalDate.of(2025, 3, 1));
        p2.setAmount(new BigDecimal("24619.16"));

        ScheduleResult schedule = scheduleService.compute(loan, List.of(), List.of(p1, p2), List.of(), LocalDate.of(2025, 1, 1));
        assertEquals(60, schedule.installments().size());
        assertEquals("settled", schedule.installments().get(0).status());
        assertEquals("settled", schedule.installments().get(1).status());
        assertEquals("upcoming", schedule.installments().get(2).status());

        when(loanService.getAllLoansWithSchedule()).thenReturn(List.of(new LoanService.LoanWithSchedule(loan, schedule)));

        List<Map<String, Object>> rows = datasource.rows();
        assertEquals(2, rows.size(), "Should only emit settled installments");

        Map<String, Object> r1 = rows.get(0);
        assertEquals(p1Id.toString(), r1.get("id"));
        assertEquals(loanId.toString(), r1.get("loanId"));
        assertEquals("HDFC Home Loan", r1.get("loanName"));
        assertEquals("home", r1.get("loanType"));
        assertEquals("HDFC", r1.get("lender"));
        assertEquals(1, r1.get("installmentSeq"));
        assertEquals(LocalDate.of(2025, 2, 1), r1.get("dueDate"));
        assertEquals(LocalDate.of(2025, 2, 1), r1.get("paymentDate"));
        assertEquals(new BigDecimal("25000.00"), r1.get("paidAmount"));
        assertEquals(schedule.installments().get(0).emi(), r1.get("scheduledEmi"));
        assertEquals(new BigDecimal("8500.00"), r1.get("interestComponent")); // schedule derived split
        assertEquals(schedule.installments().get(0).principal(), r1.get("principalComponent"));

        Map<String, Object> r2 = rows.get(1);
        assertEquals(p2Id.toString(), r2.get("id"));
        assertEquals(2, r2.get("installmentSeq"));
        assertEquals(new BigDecimal("24619.16"), r2.get("paidAmount"));
    }
}
