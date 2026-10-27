package com.financeos.api.epf.dto;

import com.financeos.domain.epf.EpfEntryType;
import com.financeos.domain.epf.EpfWithdrawalKind;
import org.springframework.lang.Nullable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record EpfEntryRequest(
        EpfEntryType type,
        @Nullable EpfWithdrawalKind withdrawalKind,
        UUID employerId,
        @Nullable LocalDate wageMonth,
        LocalDate creditedOn,
        @Nullable Integer interestFy,
        @Nullable BigDecimal employeeAmount,
        @Nullable BigDecimal employerAmount,
        @Nullable BigDecimal pensionAmount,
        @Nullable BigDecimal tdsAmount,
        @Nullable String note,
        /** TRANSFER_IN only: the employer the money came from (a TRANSFER_OUT row is created there). */
        @Nullable UUID fromEmployerId,
        /** WITHDRAWAL only on create: the bank credit it landed as. */
        @Nullable UUID transactionId) {
}
