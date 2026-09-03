package com.financeos.api.rules.dto;

import com.financeos.core.validation.MccCode;
import org.springframework.lang.Nullable;
import java.util.List;
import java.util.UUID;

public record UpdateRuleRequest(
        String merchantKey,
        String matchType,
        String displayName,
        List<UUID> categoryIds,
        @Nullable @MccCode String mcc
) {}
