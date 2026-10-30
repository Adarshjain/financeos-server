package com.financeos.domain.transaction.autolink;

/**
 * What a stored suggestion proposes, and the kind of an auto-link count or rejection. EMI, LENDING and
 * DIVIDEND are linked and remembered only: their suggestions are the existing per-feature match views,
 * so no {@code link_suggestions} row of these kinds is ever stored.
 */
public enum SuggestionKind {
    TRANSFER,
    CC_PAYMENT,
    REFUND,
    EMI,
    LENDING,
    DIVIDEND
}
