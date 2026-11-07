package com.financeos.api.gmail.dto;

import java.util.List;
import java.util.UUID;

/**
 * {@code reactivatedCount} mails inside the account's window were queued for reprocessing, {@code skippedCount} older
 * ones were marked skipped (before the window), {@code remainingCount} stay unresolved.
 */
public record AssignNumberGroupResponse(UUID identifierId, int reactivatedCount, int skippedCount, int remainingCount, List<UUID> jobIds) {}
