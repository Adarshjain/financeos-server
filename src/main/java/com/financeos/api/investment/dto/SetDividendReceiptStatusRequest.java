package com.financeos.api.investment.dto;

import com.financeos.domain.investment.dividend.DividendReceiptStatus;

import org.springframework.lang.Nullable;

/**
 * Body of {@code PUT /investments/dividends/{id}/receipt-status}. Only the manual values
 * ({@code received_untracked}, {@code not_received}) are accepted; {@code null} clears the override.
 */
public record SetDividendReceiptStatusRequest(
        @Nullable DividendReceiptStatus status
) {}
