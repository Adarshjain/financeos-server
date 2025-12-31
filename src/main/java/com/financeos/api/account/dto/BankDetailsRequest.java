package com.financeos.api.account.dto;

import java.math.BigDecimal;

public record BankDetailsRequest(
        BigDecimal openingBalance,
        String last4
) {}

