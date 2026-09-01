package com.financeos.api.account.dto;

import com.financeos.domain.account.card.Card;

import java.time.LocalDate;
import java.util.UUID;

public record CardResponse(
        UUID id,
        UUID cardholderId,
        String last4,
        LocalDate issuedOn,
        LocalDate closedOn,
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
