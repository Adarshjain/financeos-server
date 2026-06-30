package com.financeos.gmail.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface GmailProcessedMessageRepository extends JpaRepository<GmailProcessedMessage, UUID> {
    Optional<GmailProcessedMessage> findByConnectionIdAndGmailMessageId(UUID connectionId, String gmailMessageId);
    boolean existsByConnectionIdAndGmailMessageId(UUID connectionId, String gmailMessageId);
}
