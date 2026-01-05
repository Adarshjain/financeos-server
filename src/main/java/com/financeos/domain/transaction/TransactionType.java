package com.financeos.domain.transaction;

/**
 * Represents the type of a transaction.
 * <p>
 * DEBIT: Money going out (expenses, charges, withdrawals)
 * CREDIT: Money coming in (income, payments, deposits)
 * <p>
 * Note: This is an internal representation. The API uses signed amounts
 * where negative = DEBIT and positive = CREDIT.
 */
public enum TransactionType {
    DEBIT,
    CREDIT
}
