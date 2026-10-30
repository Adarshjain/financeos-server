package com.financeos.domain.transaction.autolink;

/** What a stored suggestion proposes; step 2 adds more kinds (EMI, lending, dividend). */
public enum SuggestionKind {
    TRANSFER,
    CC_PAYMENT,
    REFUND
}
