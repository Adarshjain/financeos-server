package com.financeos.api.gmail.dto;

import org.springframework.lang.Nullable;

import java.util.List;
import java.util.UUID;

/** {@code cardId} is set (and {@code identifierId} null) when the number was saved as an old card. */
public record AssignAttentionResponse(
        @Nullable UUID identifierId,
        int reactivatedCount,
        List<UUID> jobIds,
        @Nullable UUID cardId
) {
    public AssignAttentionResponse(UUID identifierId, int reactivatedCount, List<UUID> jobIds) {
        this(identifierId, reactivatedCount, jobIds, null);
    }
}
