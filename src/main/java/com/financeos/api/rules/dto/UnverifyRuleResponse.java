package com.financeos.api.rules.dto;

import java.util.List;
import java.util.UUID;

public record UnverifyRuleResponse(List<UUID> restoredTransactionIds) {}
