package com.financeos.api.epf.dto;

import com.financeos.domain.epf.EpfEmployerStatus;
import org.springframework.lang.Nullable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record EpfEmployerResponse(
        UUID id,
        String name,
        @Nullable String memberId,
        @Nullable String establishmentId,
        @Nullable String uan,
        @Nullable LocalDate joinedOn,
        @Nullable LocalDate leftOn,
        EpfEmployerStatus status,
        /** Date of the settlement or transfer-out entry behind a TRANSFERRED / SETTLED status. */
        @Nullable LocalDate statusDate,
        BigDecimal balance,
        /** The employee and employer split of {@code balance}, to prefill a final settlement. */
        BigDecimal employeeBalance,
        BigDecimal employerBalance,
        /** Gross of its TRANSFER_OUT entries; null when there are none. */
        @Nullable BigDecimal transferredOutAmount,
        /** Employer on the other side of the latest transfer out that has one. */
        @Nullable String transferredToEmployerName,
        /** Whether a pension (EPS) withdrawal is recorded. */
        boolean pensionWithdrawn) {
}
