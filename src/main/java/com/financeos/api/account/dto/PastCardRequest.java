package com.financeos.api.account.dto;

import jakarta.validation.constraints.NotNull;
import org.springframework.lang.Nullable;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Saves a number as a card that is already closed (an old card) on an account: the holder it belongs to (null = the
 * primary holder), when it was issued and when it closed (both on or before today, closed not before issued).
 */
public record PastCardRequest(
        @Nullable UUID cardholderId,
        @NotNull(message = "Opened on is required") LocalDate issuedOn,
        @NotNull(message = "Closed on is required") LocalDate closedOn
) {}
