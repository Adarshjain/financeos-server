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
     * The last day an anchor statement bills: its period end, or the date of its latest line when
     * that comes earlier. Some issuers cut a statement by posting date and print each line's
     * transaction date, a day or more earlier, so purchases dated on the period end itself are not
     * on the statement: they are unbilled and the balance adds them to the closing balance. A line
     * dated after the period end never moves the cutoff later, and a statement without lines keeps
     * its period end. Every transaction dated strictly after the cutoff counts; one dated on or
     * before it is taken as billed (or a duplicate of a billed line).
     *
     * @param periodEnd the anchor statement's period end; null means no anchor (returns null)
     * @param lastLineDate the latest date among the statement's linked lines, or null
     */
    @Nullable
    public static LocalDate cutoff(@Nullable LocalDate periodEnd, @Nullable LocalDate lastLineDate) {
        if (periodEnd == null) {
            return null;
        }
        return lastLineDate != null && lastLineDate.isBefore(periodEnd) ? lastLineDate : periodEnd;
    }

    /**
     * {@link #apply(Account, LocalDate, LocalDate, BigDecimal, BigDecimal, BigDecimal)} for a
     * statement whose cutoff is its period end (lines through the period end, or none known).
     */
    public static void apply(
            Account account,
            @Nullable LocalDate anchorDate,
            @Nullable BigDecimal anchorClosingBalance,
            @Nullable BigDecimal totalSum,
            @Nullable BigDecimal postAnchorSum
    ) {
        apply(account, anchorDate, anchorDate, anchorClosingBalance, totalSum, postAnchorSum);
    }

    /**
     * Applies balance calculations to an {@link Account} entity based on its anchor statement
     * details and transaction sums.
     *
     * @param account The account to update.
     * @param anchorDate The anchor statement end date, or {@code null} if no anchor exists.
     * @param anchorCutoff The last day the anchor statement bills ({@link #cutoff}); {@code null}
     *                     falls back to {@code anchorDate}.
     * @param anchorClosingBalance The anchor statement closing balance, or {@code null}.
     * @param totalSum Total sum of all transactions for this account, or {@code null}.
     * @param postAnchorSum Sum of transactions dated strictly after the cutoff, or {@code null}.
     */
    public static void apply(
            Account account,
            @Nullable LocalDate anchorDate,
            @Nullable LocalDate anchorCutoff,
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
            account.setAnchorCutoff(anchorCutoff != null ? anchorCutoff : anchorDate);

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
            account.setAnchorCutoff(null);
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
