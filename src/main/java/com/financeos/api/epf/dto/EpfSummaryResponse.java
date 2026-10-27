package com.financeos.api.epf.dto;

import org.springframework.lang.Nullable;

import java.math.BigDecimal;
import java.time.LocalDate;

public record EpfSummaryResponse(
        boolean hasData,
        BigDecimal balance,
        /** Employee share contributed in the current financial year. */
        BigDecimal fyEmployee,
        /** Employer share contributed in the current financial year. */
        BigDecimal fyEmployer,
        @Nullable EpfInterestEstimate interestEstimate,
        @Nullable EpfLastInterest lastInterest,
        @Nullable EpfPension pension,
        /** Wage month of the latest contribution when a current employer has gone quiet for 45+ days. */
        @Nullable LocalDate staleThrough,
        @Nullable String uanLast4) {

    /** Interest earned so far this financial year, not yet credited: {@code rate} is the percent used, {@code rateFy} the year it was declared for. */
    public record EpfInterestEstimate(int fy, BigDecimal amount, BigDecimal rate, int rateFy) {
    }

    public record EpfLastInterest(int fy, BigDecimal amount, @Nullable BigDecimal rate, LocalDate creditedOn) {
    }

    public record EpfPension(BigDecimal monthlyContribution, int serviceMonths, BigDecimal estimatedMonthlyPension,
                          boolean eligible) {
    }
}
