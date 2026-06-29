package com.financeos.gmail.ingest;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface GmailSenderRepository extends JpaRepository<GmailSender, UUID> {
    List<GmailSender> findByUserIdAndEnabledTrue(UUID userId);
    List<GmailSender> findByUserId(UUID userId);
    Optional<GmailSender> findByUserIdAndSenderAddressIgnoreCase(UUID userId, String senderAddress);
}
