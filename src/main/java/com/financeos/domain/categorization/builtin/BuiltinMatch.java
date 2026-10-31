package com.financeos.domain.categorization.builtin;

/**
 * What the built-in categoriser decided. {@code category} is null when {@code step} is {@link Step#LINK}
 * (one leg of a money move, left uncategorised); {@code key} names the rule, merchant name or MCC that fired.
 */
public record BuiltinMatch(String category, Step step, String key) {}
