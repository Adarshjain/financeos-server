package com.financeos.gmail.ingest;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface GmailSenderRemovalRepository extends JpaRepository<GmailSenderRemoval, UUID> {
    List<GmailSenderRemoval> findByUserId(UUID userId);
    void deleteByUserIdAndSenderAddressIgnoreCase(UUID userId, String senderAddress);
    boolean existsByUserIdAndSenderAddressIgnoreCase(UUID userId, String senderAddress);
}
