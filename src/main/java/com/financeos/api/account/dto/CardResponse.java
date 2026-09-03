package com.financeos.api.account.dto;

import com.financeos.domain.account.card.Card;

import org.springframework.lang.Nullable;

import java.time.LocalDate;
import java.util.UUID;

public record CardResponse(
        UUID id,
        UUID cardholderId,
        String last4,
        @Nullable LocalDate issuedOn,
        @Nullable LocalDate closedOn,
        boolean isOpen
) {
    public static CardResponse from(Card card) {
        return new CardResponse(
                card.getId(),
                card.getCardholder().getId(),
                card.getLast4(),
                card.getIssuedOn(),
                card.getClosedOn(),
                card.isOpen()
        );
    }
}
