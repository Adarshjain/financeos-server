package com.financeos.domain.reward;

/** How merchant_pattern is matched against the transaction description. */
public enum RewardMerchantMatch {
    CONTAINS,
    STARTS_WITH,
    EXACT,
    REGEX
}
