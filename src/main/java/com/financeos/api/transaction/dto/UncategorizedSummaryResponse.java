package com.financeos.api.transaction.dto;

import java.util.List;

/**
 * The Uncategorized card: {@code count} rows without a category, in {@code groupCount} groups of two or more
 * payments to the same payee; {@code topGroups} are the three biggest of those.
 */
public record UncategorizedSummaryResponse(long count, long groupCount, List<TopGroup> topGroups) {

    public record TopGroup(String label, long count) {
    }
}
