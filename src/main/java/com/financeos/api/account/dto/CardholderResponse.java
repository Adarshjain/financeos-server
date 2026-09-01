package com.financeos.api.account.dto;

import com.financeos.domain.account.card.Cardholder;
import com.financeos.domain.account.card.CardholderRelationship;
import com.financeos.domain.account.card.CardholderRole;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record CardholderResponse(
        UUID id,
        UUID accountId,
        CardholderRole role,
        String personName,
        CardholderRelationship relationship,
        BigDecimal spendLimit,
        LocalDate openedOn,
        LocalDate closedOn,
        LocalDate effectiveClosedOn,
        boolean isEffectivelyClosed,
        String currentLast4,
        List<CardResponse> cards,
        long transactionCount
) {
    public static CardholderResponse from(Cardholder ch, long transactionCount) {
        List<CardResponse> cardList = ch.getCards() != null
                ? ch.getCards().stream().map(CardResponse::from).toList()
                : List.of();
        return new CardholderResponse(
                ch.getId(),
                ch.getAccount().getId(),
                ch.getRole(),
                ch.getPersonName(),
                ch.getRelationship(),
                ch.getSpendLimit(),
                ch.getOpenedOn(),
                ch.getClosedOn(),
                ch.effectiveClosedOn(),
                ch.isEffectivelyClosed(),
                ch.currentLast4(),
                cardList,
                transactionCount
        );
    }

    public static CardholderResponse from(Cardholder ch) {
        return from(ch, 0L);
    }
}
