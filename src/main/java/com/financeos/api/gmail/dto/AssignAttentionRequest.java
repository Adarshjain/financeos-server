package com.financeos.api.gmail.dto;

import com.financeos.domain.account.AccountIdentifierKind;
import jakarta.validation.constraints.NotNull;
import org.springframework.lang.Nullable;

import java.util.UUID;

public record AssignAttentionRequest(
        @NotNull(message = "Account ID is required")
        UUID accountId,
        @Nullable
        AccountIdentifierKind kind
) {
}
