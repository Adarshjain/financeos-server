package com.financeos.api.transaction.dto;

import org.springframework.lang.Nullable;

/** A transaction flagged "Duplicate" with the likely twin found by the duplicate matcher; the twin is null when none is left. */
public record DuplicateReviewItem(
        DuplicateReviewTransaction transaction,
        @Nullable DuplicateReviewTransaction twin
) {}
