package com.financeos.domain.ingestion.held;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface HeldUploadRepository extends JpaRepository<HeldUpload, UUID> {

    /** The user's files still waiting for an account, newest first. */
    List<HeldUpload> findByUserIdAndResolvedAtIsNullOrderByCreatedAtDesc(UUID userId);

    /** Tenancy: a held upload another user owns is simply absent. */
    Optional<HeldUpload> findByIdAndUserId(UUID id, UUID userId);
}
