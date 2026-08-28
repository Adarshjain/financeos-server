package com.financeos.api.account.dto;

import com.financeos.domain.account.card.AccountCard;
import com.financeos.domain.account.card.CardRelationship;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record AccountCardResponse(
        UUID id,
        UUID accountId,
        String label,
        String holderName,
        CardRelationship relationship,
        String last4,
        boolean isPrimary,
        LocalDate issuedOn,
        LocalDate closedOn,
        BigDecimal spendLimit,
        String note,
        /** Null when the caller did not compute it — never 0, which would read as "safe to delete". */
        Long transactionCount,
        Instant createdAt,
        Instant updatedAt
) {
    /** Use when the count is genuinely unknown (e.g. the account list, which does not query it). */
    public static AccountCardResponse withoutCount(AccountCard card) {
        return from(card, null);
    }

    public static AccountCardResponse from(AccountCard card, Long transactionCount) {
        return new AccountCardResponse(
                card.getId(),
                card.getAccount().getId(),
                card.getLabel(),
                card.getHolderName(),
                card.getRelationship(),
                card.getLast4(),
                card.isPrimary(),
                card.getIssuedOn(),
                card.getClosedOn(),
                card.getSpendLimit(),
                card.getNote(),
                transactionCount,
                card.getCreatedAt(),
                card.getUpdatedAt()
        );
    }
}
