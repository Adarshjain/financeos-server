package com.financeos.api.account.dto;

import com.financeos.domain.account.card.CardholderRelationship;

import java.math.BigDecimal;
import java.time.LocalDate;

public record UpdateCardholderRequest(
        String personName,
        CardholderRelationship relationship,
        BigDecimal spendLimit,
        LocalDate openedOn
) {}
