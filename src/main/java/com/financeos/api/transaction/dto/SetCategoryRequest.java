package com.financeos.api.transaction.dto;

import com.financeos.domain.transaction.CategorySource;
import org.springframework.lang.Nullable;

import java.time.Instant;
import java.util.UUID;

/**
 * The picker's tap-to-save. Null keys clear the category. Without {@code restore} the result is
 * the user's choice (source USER); with it, the previous state an Undo returns to is put back.
 */
public record SetCategoryRequest(
        @Nullable String categoryKey,
        @Nullable String subcategoryKey,
        @Nullable Restore restore) {

    /** The state to put back; a null source restores "no category set by anyone". */
    public record Restore(@Nullable CategorySource source, @Nullable UUID ruleId, @Nullable Instant setAt) {
    }
}
