package com.financeos.domain.reward;

import java.math.BigDecimal;

/**
 * One tranche of a tiered (marginal) rate schedule, JSON-serialized into
 * reward_rules.tiers. upTo = the running matched-spend total (within the rule's
 * tier window) this tranche applies below; null = open-ended final tranche.
 * rate = percentRate for PERCENT rules, pointsPerSlab for SLAB rules.
 */
public record RewardTier(BigDecimal upTo, BigDecimal rate) {
}
