package com.financeos.domain.account;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface AccountRepositoryCustom {

    /**
     * @param latestStatementCreditLimit the credit limit printed on the account's latest non-rejected
     *                                   statement that has one (the utilisation fallback when the
     *                                   account has no limit of its own); null when none
     */
    record AccountBalanceBatch(
            UUID accountId,
            LocalDate anchorDate,
            BigDecimal anchorClosingBalance,
            BigDecimal totalSum,
            BigDecimal postAnchorSum,
            BigDecimal latestStatementCreditLimit
    ) {
        public AccountBalanceBatch(UUID accountId, LocalDate anchorDate, BigDecimal anchorClosingBalance,
                                   BigDecimal totalSum, BigDecimal postAnchorSum) {
            this(accountId, anchorDate, anchorClosingBalance, totalSum, postAnchorSum, null);
        }
    }

    Map<UUID, AccountBalanceBatch> findAccountBalanceBatches(List<UUID> accountIds);
}
