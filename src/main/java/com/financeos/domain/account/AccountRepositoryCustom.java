package com.financeos.domain.account;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface AccountRepositoryCustom {

    record AccountBalanceBatch(
            UUID accountId,
            LocalDate anchorDate,
            BigDecimal anchorClosingBalance,
            BigDecimal totalSum,
            BigDecimal postAnchorSum
    ) {}

    Map<UUID, AccountBalanceBatch> findAccountBalanceBatches(List<UUID> accountIds);
}
