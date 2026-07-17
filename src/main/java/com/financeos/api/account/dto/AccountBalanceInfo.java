package com.financeos.api.account.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record AccountBalanceInfo(
        BigDecimal anchoredBalance,
        UUID anchorStatementId,
        LocalDate anchorDate,
        Integer unreconciledTransactionCount
) {
}
