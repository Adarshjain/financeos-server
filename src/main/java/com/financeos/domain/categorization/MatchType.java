package com.financeos.domain.categorization;

/**
 * How a rule's pattern (stored in merchantKey) is matched against a transaction's
 * sourced description. MERCHANT_KEY is the original behavior — both sides are run
 * through {@link DescriptionNormalizer} — and is the only type the LLM auto-generates;
 * the other types match against the raw description, case-insensitively.
 */
public enum MatchType {

    MERCHANT_KEY(0),
    REGEX(1),
    CONTAINS(2),
    STARTS_WITH(3),
    EXACT(4);

    /**
     * Tie-break rank when multiple rules match the same description: a more literal
     * match type beats a looser one (EXACT > STARTS_WITH > CONTAINS > REGEX > MERCHANT_KEY).
     */
    private final int specificity;

    MatchType(int specificity) {
        this.specificity = specificity;
    }

    public int specificity() {
        return specificity;
    }
}
