package com.financeos.domain.investment.dividend;

/**
 * Whether the money behind a dividend row has actually landed.
 *
 * <p>Only {@link #received_untracked} and {@link #not_received} are stored (manual overrides, see
 * {@code dividends.receipt_status}); every other value is derived at read time by
 * {@link DividendReceiptWindows#derive} from the linked transaction, the expected payout window and
 * how far the user's bank data reaches.
 */
public enum DividendReceiptStatus {
    /** Linked to a bank credit. */
    received,
    /** Manual: landed in an account FinanceOS does not track. */
    received_untracked,
    /** Manual: confirmed missing, being chased with the RTA / AMC. */
    not_received,
    /** Expected payout window still open. */
    awaiting,
    /** Window passed, tracked bank data covers it, nothing matched. */
    overdue,
    /** Window passed but no tracked bank account has data that late. */
    unverifiable;

    public boolean isManual() {
        return this == received_untracked || this == not_received;
    }
}
