package com.financeos.domain.transaction.autolink;

/**
 * What a stored suggestion proposes, and the kind of an auto-link count or rejection. EMI, DIVIDEND and NPS are
 * linked and remembered only (their suggestions are the existing per-feature match views), so no
 * {@code link_suggestions} row of those kinds is stored. LENDING rows are stored: the subject is a credit
 * that looks like a repayment and the candidates are lending entry ids, not transaction ids.
 */
public enum SuggestionKind {
    TRANSFER,
    CC_PAYMENT,
    REFUND,
    EMI,
    LENDING,
    DIVIDEND,
    NPS
}
