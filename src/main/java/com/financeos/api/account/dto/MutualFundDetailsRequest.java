package com.financeos.api.account.dto;

import jakarta.validation.constraints.NotBlank;

import java.math.BigDecimal;

public record MutualFundDetailsRequest(
        @NotBlank(message = "Instrument code is required")
        String instrumentCode,

        BigDecimal lastTradedPrice
) {}

