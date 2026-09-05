package com.financeos.api.account.dto;

import com.financeos.domain.account.AccountIdentifier;
import com.financeos.domain.account.AccountIdentifierKind;

import java.time.Instant;
import java.util.UUID;

public record AccountIdentifierResponse(
        UUID id,
        String value,
        AccountIdentifierKind kind,
        Instant createdAt
) {
    public static AccountIdentifierResponse from(AccountIdentifier identifier) {
        return new AccountIdentifierResponse(
                identifier.getId(),
                identifier.getValue(),
                identifier.getKind(),
                identifier.getCreatedAt()
        );
    }
}
