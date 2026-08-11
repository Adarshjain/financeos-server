package com.financeos.domain.reward;

import java.util.List;
import java.util.UUID;

/**
 * Milestone-eligible spend filter, JSON-serialized into reward_milestones.eligibility.
 * Include lists (when non-empty): only transactions matching (category OR mcc) count.
 * Exclude lists always win over includes. All lists empty/null = everything counts.
 */
public record MilestoneEligibility(
        List<UUID> includeCategoryIds,
        List<String> includeMccs,
        List<UUID> excludeCategoryIds,
        List<String> excludeMccs) {

    public static final MilestoneEligibility EMPTY =
            new MilestoneEligibility(List.of(), List.of(), List.of(), List.of());

    public MilestoneEligibility {
        includeCategoryIds = includeCategoryIds != null ? includeCategoryIds : List.of();
        includeMccs = includeMccs != null ? includeMccs : List.of();
        excludeCategoryIds = excludeCategoryIds != null ? excludeCategoryIds : List.of();
        excludeMccs = excludeMccs != null ? excludeMccs : List.of();
    }

    public boolean isEmpty() {
        return includeCategoryIds.isEmpty() && includeMccs.isEmpty()
                && excludeCategoryIds.isEmpty() && excludeMccs.isEmpty();
    }
}
