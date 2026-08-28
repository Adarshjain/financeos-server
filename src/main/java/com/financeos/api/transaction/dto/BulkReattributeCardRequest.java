package com.financeos.api.transaction.dto;

import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.UUID;

public record BulkReattributeCardRequest(
        @NotNull(message = "Account ID is required") UUID accountId,
        UUID cardId,
        LocalDate from,
        LocalDate to,
        UUID currentCardId
) {
}
