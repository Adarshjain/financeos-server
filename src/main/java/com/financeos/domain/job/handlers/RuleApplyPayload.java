package com.financeos.domain.job.handlers;

import java.util.List;
import java.util.UUID;

public record RuleApplyPayload(UUID ruleId, Boolean all, List<UUID> transactionIds) {}
