package com.financeos.api.rules.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import com.financeos.core.validation.MccCode;
import org.springframework.lang.Nullable;
import java.util.List;
import java.util.UUID;

public record CreateRuleRequest(
        @NotBlank String merchantKey,
        String matchType,
        String displayName,
        @NotEmpty List<UUID> categoryIds,
        @Nullable @MccCode String mcc
) {}
