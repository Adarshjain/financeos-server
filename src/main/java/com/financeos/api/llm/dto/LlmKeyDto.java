package com.financeos.api.llm.dto;

import com.financeos.domain.llm.LlmKey;
import com.financeos.domain.llm.LlmKeyStatus;

import java.time.Instant;
import java.util.UUID;

public record LlmKeyDto(
        UUID id,
        String provider,
        String label,
        String keyLast4,
        LlmKeyStatus status,
        Integer position,
        Instant createdAt,
        Instant lastUsedAt
) {
    public static LlmKeyDto fromEntity(LlmKey entity) {
        return new LlmKeyDto(
                entity.getId(),
                entity.getProvider(),
                entity.getLabel(),
                entity.getKeyLast4(),
                entity.getStatus(),
                entity.getPosition(),
                entity.getCreatedAt(),
                entity.getLastUsedAt()
        );
    }
}
