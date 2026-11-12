package com.financeos.api.loan.dto;

import org.springframework.lang.Nullable;

import java.math.BigDecimal;
import java.time.LocalDate;

public record LoanCloseEvidence(
        Integer oldestUnpaidSeq,
        LocalDate oldestUnpaidDueDate,
        int daysOverdue,
        @Nullable LocalDate lastPaymentDate,
        @Nullable BigDecimal lastPaymentAmount,
        int settledInstallments,
        int totalInstallments,
        BigDecimal outstandingPrincipal
) {}
