package com.financeos.api.gmail.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.lang.Nullable;

/** Names an unknown-number group: the catalog bank id (null when the group has none) and the 4 digits. */
public record NumberGroupRequest(
        @Nullable String bank,
        @NotBlank(message = "last4 is required")
        @Pattern(regexp = "^[0-9]{4}$", message = "last4 must be exactly 4 digits")
        String last4
) {}
