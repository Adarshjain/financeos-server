package com.financeos.domain.categorization;

/**
 * How a rule's pattern (stored in merchantKey) is matched against a transaction's
 * sourced description. All types match against the raw description, case-insensitively.
 */
public enum MatchType {

    REGEX(0),
    CONTAINS(1),
    STARTS_WITH(2),
    EXACT(3);

    /**
     * Tie-break rank when multiple rules match the same description: a more literal
     * match type beats a looser one (EXACT > STARTS_WITH > CONTAINS > REGEX).
     */
    private final int specificity;

    MatchType(int specificity) {
        this.specificity = specificity;
    }

    public int specificity() {
        return specificity;
    }
}
