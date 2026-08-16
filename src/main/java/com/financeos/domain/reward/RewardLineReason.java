package com.financeos.domain.reward;

/** Why a transaction earned (or didn't earn) on a reward line — the explainability trace. */
public enum RewardLineReason {
    /** Earned in full under the matched rule. */
    MATCHED,
    /** Earned, but clamped by a per-transaction or period cap. */
    PARTIAL_CAP,
    /** Matched a rule whose period cap was already exhausted (and no fall-through paid). */
    CAP_EXHAUSTED,
    /** Matched a zero-rate rule — an explicit exclusion. */
    EXCLUDED_BY_RULE,
    /** Matched a slab rule but the basis was below one slab. */
    BELOW_SLAB,
    /** Matched a percent rule but rounding floored the cashback to zero. */
    ROUNDED_TO_ZERO,
    /** Matched a tiered rule whose applicable tier(s) yield zero at the current window spend level. */
    TIER_ZERO,
    /** No rule matched this transaction. */
    NO_RULE,
    /** Linked refunds reduced the basis to zero. */
    FULLY_REFUNDED,
    /** The matched rule excludes fees and the surcharge consumed the whole basis. */
    FEE_ONLY,
    /** Transfer / credit-card payment / reversal leg — never earns. */
    TRANSFER_OR_PAYMENT,
    /** Transaction is marked excluded from analytics. */
    TXN_EXCLUDED,
    /** Card membership fee transaction — never earns. */
    CARD_FEE
}
