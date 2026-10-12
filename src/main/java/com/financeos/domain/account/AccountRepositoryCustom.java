package com.financeos.domain.account;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface AccountRepositoryCustom {

    /**
     * @param anchorDate the anchor statement's period end; null when the account has no anchor
     * @param postAnchorSum the signed sum of the transactions dated strictly after the cutoff
     * @param latestStatementCreditLimit the credit limit printed on the account's latest non-rejected
     *                                   statement that has one (the utilisation fallback when the
     *                                   account has no limit of its own); null when none
     * @param anchorCutoff the last day the anchor statement bills ({@link BalanceMath#cutoff}); the
     *                     period end when the statement has no line dated earlier, null without an anchor
     */
    record AccountBalanceBatch(
            UUID accountId,
            LocalDate anchorDate,
            BigDecimal anchorClosingBalance,
            BigDecimal totalSum,
            BigDecimal postAnchorSum,
            BigDecimal latestStatementCreditLimit,
            LocalDate anchorCutoff
    ) {
        /** A batch whose statement bills through its period end. */
        public AccountBalanceBatch(UUID accountId, LocalDate anchorDate, BigDecimal anchorClosingBalance,
                                   BigDecimal totalSum, BigDecimal postAnchorSum, BigDecimal latestStatementCreditLimit) {
            this(accountId, anchorDate, anchorClosingBalance, totalSum, postAnchorSum, latestStatementCreditLimit, anchorDate);
        }

        public AccountBalanceBatch(UUID accountId, LocalDate anchorDate, BigDecimal anchorClosingBalance,
                                   BigDecimal totalSum, BigDecimal postAnchorSum) {
            this(accountId, anchorDate, anchorClosingBalance, totalSum, postAnchorSum, null, anchorDate);
        }
    }

    Map<UUID, AccountBalanceBatch> findAccountBalanceBatches(List<UUID> accountIds);
}
