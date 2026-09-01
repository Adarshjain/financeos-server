package com.financeos.api.account.dto;

import com.financeos.domain.account.card.CardholderRelationship;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;
import java.time.LocalDate;

public record CreateCardholderRequest(
        String personName,
        CardholderRelationship relationship,
        BigDecimal spendLimit,
        @Pattern(regexp = "^[0-9]{4}$", message = "last4 must be exactly 4 digits")
        String last4,
        LocalDate openedOn,
        LocalDate issuedOn
) {}
