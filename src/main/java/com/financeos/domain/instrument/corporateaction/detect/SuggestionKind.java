package com.financeos.domain.instrument.corporateaction.detect;

/** What a suggestion is about; the first five mirror {@code NseSubjectParser} types, {@code oversold} is a later check. */
public enum SuggestionKind {
    split,
    bonus,
    demerger,
    merger,
    other,
    oversold
}
