package com.financeos.domain.ingestion.held;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface HeldUploadRepository extends JpaRepository<HeldUpload, UUID> {

    /** The user's files still waiting for an account, newest first. */
    List<HeldUpload> findByUserIdAndResolvedAtIsNullOrderByCreatedAtDesc(UUID userId);

    /** Tenancy: a held upload another user owns is simply absent. */
    Optional<HeldUpload> findByIdAndUserId(UUID id, UUID userId);

    /** Claims an unresolved row (1) or finds it already taken (0): the guard against two concurrent assigns. */
    @Modifying
    @Transactional
    @Query("update HeldUpload h set h.resolvedAt = :at where h.id = :id and h.resolvedAt is null")
    int claim(@Param("id") UUID id, @Param("at") Instant at);

    /** Gives a claimed row back when the assign did not finish. */
    @Modifying
    @Transactional
    @Query("update HeldUpload h set h.resolvedAt = null where h.id = :id")
    int release(@Param("id") UUID id);
}
