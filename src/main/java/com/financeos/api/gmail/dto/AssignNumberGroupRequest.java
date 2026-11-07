package com.financeos.api.gmail.dto;

import com.financeos.domain.account.AccountIdentifierKind;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.lang.Nullable;

import java.util.UUID;

public record AssignNumberGroupRequest(
        @Nullable String bank,
        @NotBlank(message = "last4 is required")
        @Pattern(regexp = "^[0-9]{4}$", message = "last4 must be exactly 4 digits")
        String last4,
        @NotNull(message = "Account ID is required")
        UUID accountId,
        @Nullable AccountIdentifierKind kind
) {}
