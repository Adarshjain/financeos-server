package com.financeos.api.epf.dto;

import com.financeos.domain.epf.EpfEntryType;
import com.financeos.domain.epf.EpfEntrySource;
import com.financeos.domain.epf.EpfWithdrawalKind;
import org.springframework.lang.Nullable;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record EpfEntryResponse(
        UUID id,
        UUID employerId,
        String employerName,
        EpfEntryType type,
        @Nullable EpfWithdrawalKind withdrawalKind,
        @Nullable LocalDate wageMonth,
        LocalDate creditedOn,
        @Nullable Integer interestFy,
        BigDecimal employeeAmount,
        BigDecimal employerAmount,
        BigDecimal pensionAmount,
        @Nullable BigDecimal tdsAmount,
        /** Withdrawals only: employee + employer (pension amount for a pension withdrawal). */
        @Nullable BigDecimal grossAmount,
        /** Withdrawals only: gross minus TDS. */
        @Nullable BigDecimal receivedAmount,
        @Nullable String note,
        @Nullable String particulars,
        EpfEntrySource source,
        @Nullable Instant importedAt,
        @Nullable UUID counterpartEmployerId,
        @Nullable String counterpartEmployerName,
        @Nullable EpfLinkedTransaction transaction,
        /** An unlinked withdrawal's likely bank credit. */
        @Nullable EpfLinkedTransaction suggestion,
        /** That employer's EPF balance after this entry. */
        BigDecimal runningBalance) {

    public record EpfLinkedTransaction(UUID id, LocalDate date, @Nullable String description, @Nullable String accountName,
                                    BigDecimal amount) {
    }
}
