package com.financeos.api.gmail.dto;

import java.util.List;
import java.util.UUID;

public record AssignAttentionResponse(
        UUID identifierId,
        int reactivatedCount,
        List<UUID> jobIds
) {
}
