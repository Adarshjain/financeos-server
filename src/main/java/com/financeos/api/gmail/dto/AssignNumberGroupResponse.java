package com.financeos.api.gmail.dto;

import org.springframework.lang.Nullable;

import java.util.List;
import java.util.UUID;

/**
 * {@code reactivatedCount} mails inside the account's window were queued for reprocessing, {@code skippedCount} older
 * ones were marked skipped (before the window), {@code remainingCount} stay unresolved. {@code cardId} is set (and
 * {@code identifierId} null) when the number was saved as an old card.
 */
public record AssignNumberGroupResponse(@Nullable UUID identifierId, int reactivatedCount, int skippedCount, int remainingCount, List<UUID> jobIds,
                                        @Nullable UUID cardId) {
    /** An identifier was added (no card). */
    public AssignNumberGroupResponse(UUID identifierId, int reactivatedCount, int skippedCount, int remainingCount, List<UUID> jobIds) {
        this(identifierId, reactivatedCount, skippedCount, remainingCount, jobIds, null);
    }
}
