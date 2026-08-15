package com.financeos.domain.reward;

/**
 * How a rule treats the labeled convenience-fee / surcharge portion of a transaction.
 * Unlike EMI and international treatment — which are match predicates that filter the
 * transaction in or out — this adjusts the earning basis of a transaction that already
 * matched: many issuers post the surcharge to the card but never award points on it.
 */
public enum FeeTreatment {
    /** Fee earns like the rest of the spend (default; matches pre-existing behavior). */
    INCLUDE,
    /** Fee is netted out of the basis before accrual — the issuer does not reward it. */
    EXCLUDE_FEE
}
