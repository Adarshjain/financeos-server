package com.financeos.domain.lending;

/**
 * What a ledger entry does to the balance with a counterparty.
 * <ul>
 *   <li>{@code principal}: new money lent or borrowed (creates debt).</li>
 *   <li>{@code settlement}: a repayment that clears an existing balance.</li>
 * </ul>
 * {@link LendingDirection} still says which way the money moved, so net position,
 * running balance and the transaction DEBIT/CREDIT rule are kind-agnostic; only the
 * gross "total lent / total borrowed" figures exclude settlements.
 */
public enum LendingKind {
    principal,
    settlement
}
