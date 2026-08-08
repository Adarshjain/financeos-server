package com.financeos.domain.loan.schedule;

import com.financeos.api.loan.dto.InstallmentDto;
import com.financeos.core.exception.ValidationException;
import com.financeos.domain.loan.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class LoanScheduleServiceTest {

    private LoanScheduleService scheduleService;

    @BeforeEach
    void setUp() {
        scheduleService = new LoanScheduleService();
    }

    private Loan createBaseLoan(BigDecimal principal, BigDecimal ratePct, int tenureMonths) {
        Loan loan = new Loan();
        loan.setId(UUID.randomUUID());
        loan.setName("Test Loan");
        loan.setLoanType(LoanType.home);
        loan.setLender("HDFC");
        loan.setPrincipal(principal);
        loan.setAnnualRatePct(ratePct);
        loan.setRateType(RateType.fixed);
        loan.setTenureMonths(tenureMonths);
        loan.setStartDate(LocalDate.of(2025, 1, 1));
        loan.setFirstEmiDate(LocalDate.of(2025, 2, 1));
        loan.setStatus(LoanStatus.active);
        return loan;
    }

    @Test
    void testA_PlainFixedLoan_12Lakhs_8Point5Percent_60Months() {
        // 12L @ 8.5% for 60m
        Loan loan = createBaseLoan(new BigDecimal("1200000"), new BigDecimal("8.5"), 60);

        ScheduleResult result = scheduleService.compute(loan, null, null, null, LocalDate.of(2025, 1, 1));

        assertEquals(60, result.installments().size());

        InstallmentDto firstRow = result.installments().get(0);
        // EMI ≈ 24619.16
        assertTrue(firstRow.emi().compareTo(new BigDecimal("24600")) > 0 && firstRow.emi().compareTo(new BigDecimal("24700")) < 0);
        // First row interest: 1200000 * 0.085 / 12 = 8500.00
        assertEquals(new BigDecimal("8500.00"), firstRow.interest());

        // Assert sum of principal == 12,00,000.00
        BigDecimal sumPrincipal = result.installments().stream()
                .map(InstallmentDto::principal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(new BigDecimal("1200000.00"), sumPrincipal.setScale(2, RoundingMode.HALF_UP));
    }

    @Test
    void testB_RateChange_ReduceTenure() {
        Loan loan = createBaseLoan(new BigDecimal("500000"), new BigDecimal("10.0"), 36);

        LoanEvent rateChange = new LoanEvent();
        rateChange.setEventType(LoanEventType.rate_change);
        rateChange.setEffectiveDate(LocalDate.of(2025, 6, 1)); // After 4 installments
        rateChange.setNewAnnualRatePct(new BigDecimal("12.0"));
        rateChange.setAdjustmentMode(AdjustmentMode.reduce_tenure);

        ScheduleResult result = scheduleService.compute(loan, List.of(rateChange), null, null, LocalDate.of(2025, 6, 15));

        // Rate increase with reduce_tenure should keep EMI and increase total installments
        assertTrue(result.installments().size() > 36);
        assertEquals(new BigDecimal("12.00"), result.currentAnnualRatePct());
    }

    @Test
    void testC_Prepayment_ReduceTenure() {
        Loan loan = createBaseLoan(new BigDecimal("1000000"), new BigDecimal("9.0"), 120);

        LoanEvent prepayment = new LoanEvent();
        prepayment.setEventType(LoanEventType.prepayment);
        prepayment.setEffectiveDate(LocalDate.of(2025, 6, 1));
        prepayment.setAmount(new BigDecimal("300000"));
        prepayment.setAdjustmentMode(AdjustmentMode.reduce_tenure);

        ScheduleResult result = scheduleService.compute(loan, List.of(prepayment), null, null, LocalDate.of(2025, 1, 1));

        // Prepayment should significantly shorten schedule
        assertTrue(result.installments().size() < 120);

        BigDecimal sumPrincipal = result.installments().stream()
                .map(InstallmentDto::principal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // Sum of installments principal + prepayment == original principal
        assertEquals(new BigDecimal("1000000.00"), sumPrincipal.add(new BigDecimal("300000")).setScale(2, RoundingMode.HALF_UP));
    }

    @Test
    void testD_Prepayment_ReduceEmi() {
        Loan loan = createBaseLoan(new BigDecimal("1000000"), new BigDecimal("9.0"), 120);

        LoanEvent prepayment = new LoanEvent();
        prepayment.setEventType(LoanEventType.prepayment);
        prepayment.setEffectiveDate(LocalDate.of(2025, 6, 1));
        prepayment.setAmount(new BigDecimal("200000"));
        prepayment.setAdjustmentMode(AdjustmentMode.reduce_emi);

        ScheduleResult result = scheduleService.compute(loan, List.of(prepayment), null, null, LocalDate.of(2025, 1, 1));

        // EMI should be reduced for installments after prepayment
        BigDecimal initialEmi = result.installments().get(0).emi();
        BigDecimal postPrepaymentEmi = result.installments().get(10).emi();

        assertTrue(postPrepaymentEmi.compareTo(initialEmi) < 0);
    }

    @Test
    void testE_NewEmiOverride_WinsOverComputedAnnuity() {
        Loan loan = createBaseLoan(new BigDecimal("500000"), new BigDecimal("10.0"), 36);

        LoanEvent rateChange = new LoanEvent();
        rateChange.setEventType(LoanEventType.rate_change);
        rateChange.setEffectiveDate(LocalDate.of(2025, 4, 1));
        rateChange.setNewAnnualRatePct(new BigDecimal("11.0"));
        rateChange.setAdjustmentMode(AdjustmentMode.reduce_emi);
        rateChange.setNewEmiOverride(new BigDecimal("18000.00"));

        ScheduleResult result = scheduleService.compute(loan, List.of(rateChange), null, null, LocalDate.of(2025, 1, 1));

        // Installments after effectiveDate should use newEmiOverride
        InstallmentDto row5 = result.installments().get(4); // May installment
        assertEquals(new BigDecimal("18000.00"), row5.emi());
    }

    @Test
    void testF_Foreclosure_TruncatesScheduleAndZeroesOutstanding() {
        Loan loan = createBaseLoan(new BigDecimal("600000"), new BigDecimal("9.5"), 60);

        LoanEvent foreclosure = new LoanEvent();
        foreclosure.setEventType(LoanEventType.foreclosure);
        foreclosure.setEffectiveDate(LocalDate.of(2025, 7, 15));
        foreclosure.setAmount(new BigDecimal("550000"));

        ScheduleResult result = scheduleService.compute(loan, List.of(foreclosure), null, null, LocalDate.of(2025, 8, 1));

        // Schedule should only have installments before July 15, 2025 (6 installments: Feb to Jul)
        assertEquals(6, result.installments().size());
        assertEquals(new BigDecimal("0.00"), result.outstandingPrincipal());
    }

    @Test
    void testG_NonAmortizingEmi_Rejected() {
        Loan loan = createBaseLoan(new BigDecimal("1000000"), new BigDecimal("12.0"), 120);
        // Interest per month = 10,00,000 * 0.12 / 12 = 10,000
        loan.setEmiAmount(new BigDecimal("8000.00")); // Less than interest!

        ValidationException ex = assertThrows(ValidationException.class, () ->
                scheduleService.compute(loan, null, null, null, LocalDate.of(2025, 1, 1))
        );
        assertTrue(ex.getMessage().contains("non-amortizing"));
    }

    @Test
    void testH_Exceeding600Installments_Rejected() {
        Loan loan = createBaseLoan(new BigDecimal("10000000"), new BigDecimal("15.0"), 600);
        // Set EMI barely above interest so it takes >600 months
        // Monthly interest = 10,000,000 * 0.15 / 12 = 125,000
        loan.setEmiAmount(new BigDecimal("125001.00"));

        ValidationException ex = assertThrows(ValidationException.class, () ->
                scheduleService.compute(loan, null, null, null, LocalDate.of(2025, 1, 1))
        );
        assertTrue(ex.getMessage().contains("600 installments"));
    }

    @Test
    void testI_SettlementOverlay_StatusesAroundFixedToday() {
        Loan loan = createBaseLoan(new BigDecimal("120000"), new BigDecimal("10.0"), 12);

        // Payment for installment 1 (Feb 1, 2025)
        LoanPayment payment1 = new LoanPayment();
        payment1.setInstallmentSeq(1);
        payment1.setPaymentDate(LocalDate.of(2025, 2, 1));
        payment1.setAmount(new BigDecimal("10550.00"));

        // Fixed "today" is March 15, 2025
        // Seq 1 (Feb 1) -> settled
        // Seq 2 (Mar 1) -> overdue (unsettled & due < today)
        // Seq 3 (Apr 1) -> upcoming (due >= today)
        ScheduleResult result = scheduleService.compute(loan, null, List.of(payment1), null, LocalDate.of(2025, 3, 15));

        assertEquals("settled", result.installments().get(0).status());
        assertEquals("overdue", result.installments().get(1).status());
        assertEquals("upcoming", result.installments().get(2).status());
    }

    @Test
    void testJ_Apr_WithProcessingFeeCharge_HigherThanNominalRate() {
        Loan loan = createBaseLoan(new BigDecimal("100000"), new BigDecimal("10.0"), 12);

        LoanCharge fee = new LoanCharge();
        fee.setChargeType(LoanChargeType.processing_fee);
        fee.setAmount(new BigDecimal("2000")); // 2k processing fee
        fee.setChargeDate(LocalDate.of(2025, 1, 1));

        ScheduleResult result = scheduleService.compute(loan, null, null, List.of(fee), LocalDate.of(2025, 1, 1));

        assertNotNull(result.effectiveAprPct());
        // Effective APR should be higher than nominal 10.0%
        assertTrue(result.effectiveAprPct() > 10.0, "Expected effective APR (" + result.effectiveAprPct() + ") to be > 10.0%");
    }
}
