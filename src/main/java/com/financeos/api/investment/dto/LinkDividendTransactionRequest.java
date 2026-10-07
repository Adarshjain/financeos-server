package com.financeos.api.investment.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Body of {@code PUT /investments/dividends/{id}/transaction}: attach (or replace) the bank credit.
 * {@code updateTds} writes {@code tds = gross − received} onto the row when no TDS is recorded and the
 * gap looks like tax deducted at source (≤ 25% of gross).
 */
public record LinkDividendTransactionRequest(
        @NotNull UUID transactionId,
        boolean updateTds
) {}
