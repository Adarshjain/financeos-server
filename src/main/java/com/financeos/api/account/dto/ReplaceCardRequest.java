package com.financeos.api.account.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import java.time.LocalDate;

public record ReplaceCardRequest(
        @NotBlank(message = "newLast4 is required")
        @Pattern(regexp = "^[0-9]{4}$", message = "newLast4 must be exactly 4 digits")
        String newLast4,
        LocalDate issuedOn
) {}
