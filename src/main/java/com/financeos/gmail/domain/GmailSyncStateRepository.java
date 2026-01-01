package com.financeos.gmail.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface GmailSyncStateRepository extends JpaRepository<GmailSyncStateEntity, UUID> {

    Optional<GmailSyncStateEntity> findByConnectionId(UUID connectionId);
}

