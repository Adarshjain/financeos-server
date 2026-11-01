package com.financeos.domain.categorization.builtin;

/**
 * What the built-in categoriser decided. {@code categoryKey}/{@code subcategoryKey} are taxonomy keys, both null
 * when nothing answered, when {@code step} is {@link Step#LINK} (one leg of a money move, left to the link) or
 * {@link Step#PEOPLE} ({@code person} is then true: a payment to an individual gets no category). {@code step} is
 * null when no step matched; {@code key} names the rule, merchant name or MCC that fired. {@code counterpartyName}
 * is the normalised merchant or payee name read from the input, present whether or not anything matched
 * (null only when the input names nobody).
 */
public record BuiltinMatch(String categoryKey, String subcategoryKey, Step step, String key, String counterpartyName,
                           boolean person) {

    /** True when the engine produced a category. */
    public boolean hasCategory() {
        return categoryKey != null;
    }
}
