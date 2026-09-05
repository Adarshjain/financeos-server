package com.financeos.api.account.dto;

import com.financeos.domain.account.AccountIdentifierKind;
import jakarta.validation.constraints.NotBlank;
import org.springframework.lang.Nullable;

public record CreateAccountIdentifierRequest(
        @NotBlank(message = "Identifier value is required")
        String value,
        @Nullable
        AccountIdentifierKind kind
) {
}
