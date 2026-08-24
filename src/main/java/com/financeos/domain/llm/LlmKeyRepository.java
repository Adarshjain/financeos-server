package com.financeos.domain.llm;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface LlmKeyRepository extends JpaRepository<LlmKey, UUID> {

    List<LlmKey> findByUserIdOrderByProviderAscPositionAsc(UUID userId);

    List<LlmKey> findByUserIdAndProviderOrderByPositionAsc(UUID userId, String provider);

    List<LlmKey> findByUserIdAndProviderAndStatusOrderByPositionAsc(UUID userId, String provider, LlmKeyStatus status);

    List<LlmKey> findByUserIdAndStatusOrderByPositionAsc(UUID userId, LlmKeyStatus status);

    Optional<LlmKey> findByIdAndUserId(UUID id, UUID userId);

    Optional<LlmKey> findFirstByUserIdAndProviderOrderByPositionDesc(UUID userId, String provider);
}
