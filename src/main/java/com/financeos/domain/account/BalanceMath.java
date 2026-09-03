package com.financeos.domain.account;

import org.springframework.lang.Nullable;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Pure domain balance calculation logic shared between single-account lookup
 * ({@code AccountService.getAccountById}) and batch-account listing
 * ({@code AccountService.getAllAccounts}).
 */
public final class BalanceMath {

    private static final BigDecimal GAP_THRESHOLD = new BigDecimal("0.01");

    private BalanceMath() {}

    /**
     * Applies balance calculations to an {@link Account} entity based on its anchor statement
     * details and transaction sums.
     *
     * @param account The account to update.
     * @param anchorDate The anchor statement end date, or {@code null} if no anchor exists.
     * @param anchorClosingBalance The anchor statement closing balance, or {@code null}.
     * @param totalSum Total sum of all transactions for this account, or {@code null}.
     * @param postAnchorSum Sum of transactions strictly after the anchor date, or {@code null}.
     */
    public static void apply(
            Account account,
            @Nullable LocalDate anchorDate,
            @Nullable BigDecimal anchorClosingBalance,
            @Nullable BigDecimal totalSum,
            @Nullable BigDecimal postAnchorSum
    ) {
        if (anchorDate != null && anchorClosingBalance != null) {
            BigDecimal postSum = postAnchorSum != null ? postAnchorSum : BigDecimal.ZERO;
            BigDecimal anchoredBalance;

            if (account.getType() == AccountType.credit_card) {
                BigDecimal base = anchorClosingBalance.compareTo(BigDecimal.ZERO) > 0
                        ? anchorClosingBalance.negate()
                        : anchorClosingBalance.abs();
                anchoredBalance = base.add(postSum);
            } else {
                anchoredBalance = anchorClosingBalance.add(postSum);
            }

            account.setCalculatedBalance(anchoredBalance);
            account.setBalanceAnchored(true);
            account.setAnchorDate(anchorDate);

            if (account.getType() == AccountType.bank_account
                    && account.getBankDetails() != null
                    && account.getBankDetails().getOpeningBalance() != null) {
                BigDecimal totSum = totalSum != null ? totalSum : BigDecimal.ZERO;
                BigDecimal pureTxBalance = account.getBankDetails().getOpeningBalance().add(totSum);
                BigDecimal gap = pureTxBalance.subtract(anchoredBalance);

                if (gap.abs().compareTo(GAP_THRESHOLD) >= 0) {
                    account.setReconciliationGap(gap);
                } else {
                    account.setReconciliationGap(null);
                }
            } else {
                account.setReconciliationGap(null);
            }
        } else {
            account.setBalanceAnchored(false);
            account.setAnchorDate(null);
            account.setReconciliationGap(null);

            BigDecimal totSum = totalSum != null ? totalSum : BigDecimal.ZERO;
            if (account.getType() == AccountType.bank_account
                    && account.getBankDetails() != null
                    && account.getBankDetails().getOpeningBalance() != null) {
                account.setCalculatedBalance(account.getBankDetails().getOpeningBalance().add(totSum));
            } else {
                account.setCalculatedBalance(totSum);
            }
        }
    }
}
