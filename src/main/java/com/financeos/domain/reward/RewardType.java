package com.financeos.domain.reward;

/**
 * The currency a rule's computed number is paid in. Orthogonal to {@link AccrualType}:
 * percent/slab/tiered math produces a number, the reward type says whether that
 * number is rupees of direct cashback or reward points.
 */
public enum RewardType {
    CASH,
    POINTS
}
