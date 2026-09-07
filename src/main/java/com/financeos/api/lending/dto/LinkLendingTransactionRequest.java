package com.financeos.api.lending.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/** Body of {@code PUT /lendings/{id}/transaction}: attach (or replace) the linked bank transaction. */
public record LinkLendingTransactionRequest(
        @NotNull UUID transactionId
) {}
