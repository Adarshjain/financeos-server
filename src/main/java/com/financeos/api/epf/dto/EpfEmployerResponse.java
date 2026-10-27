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
        BigDecimal balance) {
}
