package com.financeos.api.account.dto;

import com.financeos.domain.account.card.CardRelationship;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;
import java.time.LocalDate;

public record UpdateAccountCardRequest(
        String label,
        String holderName,
        @NotNull(message = "Relationship is required")
        CardRelationship relationship,
        @NotNull(message = "Last 4 digits are required")
        @Pattern(regexp = "^[0-9]{4}$", message = "Last 4 must be exactly 4 digits")
        String last4,
        LocalDate issuedOn,
        BigDecimal spendLimit,
        String note
) {
}
