package com.financeos.domain.reward;

/**
 * EXCLUSIVE rules form a priority-ordered first-match chain (with optional cap
 * fall-through); ADDITIVE rules stack on top of the exclusive winner with their
 * own caps (base-always-earns + separately-capped-bonus pattern).
 */
public enum RuleStacking {
    EXCLUSIVE,
    ADDITIVE
}
