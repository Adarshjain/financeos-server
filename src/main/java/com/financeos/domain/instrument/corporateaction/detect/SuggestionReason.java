package com.financeos.domain.instrument.corporateaction.detect;

/** Why a suggestion was held for the user instead of applied. */
public enum SuggestionReason {
    COMBINED_SAME_DAY,
    NEEDS_DETAILS,
    UNCLEAR_TEXT,
    NO_PRICE_SYMBOL,
    YAHOO_MISSING,
    YAHOO_MISMATCH,
    POSSIBLE_DUPLICATE
}
