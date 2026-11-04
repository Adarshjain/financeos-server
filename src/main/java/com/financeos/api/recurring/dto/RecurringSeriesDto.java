package com.financeos.api.recurring.dto;

import com.financeos.domain.recurring.RecurringKind;
import com.financeos.domain.recurring.RecurringPeriod;
import com.financeos.domain.recurring.RecurringStatus;
import org.springframework.lang.Nullable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** A proposed or tracked series of regular payments. {@code loanPrefill} is set on EMI series only. */
public record RecurringSeriesDto(
        UUID id,
        RecurringKind kind,
        String displayName,
        RecurringPeriod period,
        BigDecimal amount,
        LocalDate lastPaidOn,
        LocalDate nextDueOn,
        int paymentCount,
        @Nullable String accountName,
        RecurringStatus status,
        @Nullable LoanPrefillDto loanPrefill
) {
    /** What the Add loan form is opened with: the fields the loan create request accepts and the series knows. */
    public record LoanPrefillDto(
            String name,
            BigDecimal emiAmount,
            int emiDayOfMonth,
            @Nullable LocalDate firstPaymentGuess,
            @Nullable UUID accountId
    ) {
    }
}
