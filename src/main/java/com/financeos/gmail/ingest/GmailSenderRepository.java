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

    interface BankCoverage {
        String getBankId();
        java.time.Instant getCoveredFrom();
    }

    /**
     * Per bank (null = senders matching no bank) of the user's enabled senders, the earliest point their Gmail cursors
     * cover; null when a sender has no cursor yet. One query for every account.
     */
    @org.springframework.data.jpa.repository.Query("SELECT s.bankId AS bankId, MIN(c.earliestCoveredAt) AS coveredFrom "
            + "FROM GmailSender s LEFT JOIN GmailSyncCursor c ON c.sender = s "
            + "WHERE s.user.id = :userId AND s.enabled = true GROUP BY s.bankId")
    List<BankCoverage> findBankCoverage(@org.springframework.data.repository.query.Param("userId") UUID userId);
}
