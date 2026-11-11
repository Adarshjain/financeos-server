package com.financeos.domain.instrument.corporateaction.detect;

/** Where a suggestion stands: applied by the detector, waiting for the user, or settled by the user. */
public enum SuggestionStatus {
    auto_applied,
    pending,
    accepted,
    dismissed,
    undone
}
