package com.financeos.domain.categorization.builtin;

/** Which stage of the built-in categoriser decided, in the order they run. */
public enum Step {
    LINK,
    KEYWORD,
    MERCHANT,
    MCC,
    PEOPLE
}
