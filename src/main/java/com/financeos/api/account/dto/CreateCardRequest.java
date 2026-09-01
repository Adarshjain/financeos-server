package com.financeos.api.account.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import java.time.LocalDate;

public record CreateCardRequest(
        @NotBlank(message = "last4 is required")
        @Pattern(regexp = "^[0-9]{4}$", message = "last4 must be exactly 4 digits")
        String last4,
        LocalDate issuedOn
) {}
