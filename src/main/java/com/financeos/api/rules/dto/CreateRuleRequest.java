package com.financeos.api.rules.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import java.util.UUID;

public record CreateRuleRequest(
        @NotBlank String merchantKey,
        String displayName,
        @NotEmpty List<UUID> categoryIds
) {}
