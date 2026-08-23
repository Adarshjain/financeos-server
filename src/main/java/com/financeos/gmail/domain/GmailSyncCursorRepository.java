package com.financeos.gmail.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface GmailSyncCursorRepository extends JpaRepository<GmailSyncCursor, UUID> {
    List<GmailSyncCursor> findByConnectionId(UUID connectionId);
    Optional<GmailSyncCursor> findByConnectionIdAndSenderId(UUID connectionId, UUID senderId);
    void deleteBySenderId(UUID senderId);
}
