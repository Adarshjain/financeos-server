package com.financeos.api.gmail.dto;

import com.financeos.api.account.dto.PastCardRequest;
import com.financeos.domain.account.AccountIdentifierKind;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.lang.Nullable;

import java.util.UUID;

public record AssignAttentionRequest(
        @NotNull(message = "Account ID is required")
        UUID accountId,
        @Nullable
        AccountIdentifierKind kind,
        /** Set to save the email's number as an old (closed) card of the account instead of an identifier. */
        @Nullable @Valid
        PastCardRequest pastCard
) {
    public AssignAttentionRequest(UUID accountId, AccountIdentifierKind kind) {
        this(accountId, kind, null);
    }
}
