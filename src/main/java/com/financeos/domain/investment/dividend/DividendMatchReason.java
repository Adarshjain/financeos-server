package com.financeos.domain.investment.dividend;

/** Why a bank credit was proposed for a dividend row — shown to the user next to the candidate. */
public enum DividendMatchReason {
    EXACT_GROSS,
    NET_OF_RECORDED_TDS,
    NET_OF_10PCT_TDS,
    AMOUNT_WITHIN_BAND,
    /** Received ≈ gross × a common split ratio: Yahoo reports split-adjusted per-share amounts. */
    SPLIT_RATIO_SUSPECT,
    DIVIDEND_KEYWORD,
    NAME_MATCH,
    SYMBOL_MATCH
}
