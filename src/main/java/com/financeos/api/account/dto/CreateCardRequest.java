package com.financeos.api.account.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import java.time.LocalDate;
import java.util.UUID;

public record CreateCardRequest(
        @NotBlank(message = "last4 is required")
        @Pattern(regexp = "^[0-9]{4}$", message = "last4 must be exactly 4 digits")
        String last4,
        LocalDate issuedOn,
        /** The suggestion this card is added from; null = none. */
        UUID fromSuggestionId,
        /** Set to add a card that is already closed (a past card): needs {@code issuedOn}, both on or before today. */
        LocalDate closedOn
) {
    public CreateCardRequest(String last4, LocalDate issuedOn, UUID fromSuggestionId) {
        this(last4, issuedOn, fromSuggestionId, null);
    }

    public CreateCardRequest(String last4, LocalDate issuedOn) {
        this(last4, issuedOn, null, null);
    }
}
