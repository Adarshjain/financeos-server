package com.financeos.api.rules.dto;

import com.financeos.core.validation.MccCode;
import java.util.List;
import java.util.UUID;

public record UpdateRuleRequest(
        String merchantKey,
        String displayName,
        List<UUID> categoryIds,
        @MccCode String mcc
) {}
