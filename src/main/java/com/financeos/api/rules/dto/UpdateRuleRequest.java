package com.financeos.api.rules.dto;

import java.util.List;
import java.util.UUID;

public record UpdateRuleRequest(
        String merchantKey,
        String displayName,
        List<UUID> categoryIds
) {}
