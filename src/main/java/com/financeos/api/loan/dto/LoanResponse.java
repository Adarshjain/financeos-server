package com.financeos.api.loan.dto;

import com.financeos.domain.loan.Loan;
import com.financeos.domain.loan.LoanStatus;
import com.financeos.domain.loan.LoanType;
import com.financeos.domain.loan.RateType;
import com.financeos.domain.loan.schedule.ScheduleResult;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record LoanResponse(
        UUID id,
        String name,
        LoanType loanType,
        String lender,
        String loanAccountNumber,
        UUID paymentAccountId,
        BigDecimal principal,
        BigDecimal annualRatePct,
        RateType rateType,
        Integer tenureMonths,
        LocalDate startDate,
        LocalDate firstEmiDate,
        BigDecimal emiAmount,
        LoanStatus status,
        String notes,
        Instant createdAt,
        Instant updatedAt,
        BigDecimal currentAnnualRatePct,
        BigDecimal currentEmi,
        BigDecimal outstandingPrincipal,
        Integer totalInstallments,
        Integer settledInstallments,
        LocalDate nextDueDate,
        LocalDate projectedEndDate,
        BigDecimal totalInterestPaid,
        BigDecimal totalInterestRemaining,
        Double effectiveAprPct
) {
    public static LoanResponse from(Loan loan, ScheduleResult schedule) {
        return new LoanResponse(
                loan.getId(),
                loan.getName(),
                loan.getLoanType(),
                loan.getLender(),
                loan.getLoanAccountNumber(),
                loan.getPaymentAccount() != null ? loan.getPaymentAccount().getId() : null,
                loan.getPrincipal(),
                loan.getAnnualRatePct(),
                loan.getRateType(),
                loan.getTenureMonths(),
                loan.getStartDate(),
                loan.getFirstEmiDate(),
                loan.getEmiAmount(),
                loan.getStatus(),
                loan.getNotes(),
                loan.getCreatedAt(),
                loan.getUpdatedAt(),
                schedule != null ? schedule.currentAnnualRatePct() : loan.getAnnualRatePct(),
                schedule != null ? schedule.currentEmi() : loan.getEmiAmount(),
                schedule != null ? schedule.outstandingPrincipal() : loan.getPrincipal(),
                schedule != null ? schedule.totalInstallments() : loan.getTenureMonths(),
                schedule != null ? schedule.settledInstallments() : 0,
                schedule != null ? schedule.nextDueDate() : loan.getFirstEmiDate(),
                schedule != null ? schedule.projectedEndDate() : null,
                schedule != null ? schedule.totalInterestPaid() : BigDecimal.ZERO,
                schedule != null ? schedule.totalInterestRemaining() : BigDecimal.ZERO,
                schedule != null ? schedule.effectiveAprPct() : null
        );
    }
}
