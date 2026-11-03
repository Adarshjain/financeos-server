package com.financeos.api.account.dto;

import com.financeos.domain.account.card.CardholderRelationship;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record CreateCardholderRequest(
        String personName,
        CardholderRelationship relationship,
        BigDecimal spendLimit,
        @Pattern(regexp = "^[0-9]{4}$", message = "last4 must be exactly 4 digits")
        String last4,
        LocalDate openedOn,
        LocalDate issuedOn,
        /** The suggestion this add-on card is added from; null = none. */
        UUID fromSuggestionId
) {
    public CreateCardholderRequest(String personName, CardholderRelationship relationship, BigDecimal spendLimit,
                                   String last4, LocalDate openedOn, LocalDate issuedOn) {
        this(personName, relationship, spendLimit, last4, openedOn, issuedOn, null);
    }
}
