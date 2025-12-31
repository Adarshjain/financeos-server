package com.financeos.api.account.dto;

import jakarta.validation.constraints.NotBlank;

import java.math.BigDecimal;

public record StockDetailsRequest(
        @NotBlank(message = "Instrument code is required")
        String instrumentCode,

        BigDecimal lastTradedPrice
) {}

