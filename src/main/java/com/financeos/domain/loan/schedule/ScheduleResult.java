package com.financeos.domain.loan.schedule;

import com.financeos.api.loan.dto.InstallmentDto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record ScheduleResult(
        List<InstallmentDto> installments,
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
) {}
