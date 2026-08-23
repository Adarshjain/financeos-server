package com.financeos.gmail.domain;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface GmailProcessedMessageRepository extends JpaRepository<GmailProcessedMessage, UUID> {
    Optional<GmailProcessedMessage> findByConnectionIdAndGmailMessageId(UUID connectionId, String gmailMessageId);
    boolean existsByConnectionIdAndGmailMessageId(UUID connectionId, String gmailMessageId);

    @Query("SELECT g.gmailMessageId FROM GmailProcessedMessage g WHERE g.connection.id = :connectionId AND g.gmailMessageId IN :gmailMessageIds")
    List<String> findExistingMessageIds(@Param("connectionId") UUID connectionId, @Param("gmailMessageIds") Collection<String> gmailMessageIds);

    long countByConnectionIdAndStatus(UUID connectionId, GmailProcessedStatus status);

    long countByConnectionIdAndStatusIn(UUID connectionId, Collection<GmailProcessedStatus> statuses);

    long countByUserIdAndStatus(UUID userId, GmailProcessedStatus status);

    @Query("SELECT g FROM GmailProcessedMessage g WHERE g.connection.id = :connectionId AND (g.status = com.financeos.gmail.domain.GmailProcessedStatus.DISCOVERED OR (g.status = com.financeos.gmail.domain.GmailProcessedStatus.FAILED_RETRYABLE AND g.nextRetryAt <= :now)) ORDER BY g.discoveredAt ASC, g.id ASC")
    List<GmailProcessedMessage> findPendingForDrain(@Param("connectionId") UUID connectionId, @Param("now") Instant now, Pageable pageable);

    @Query("SELECT g FROM GmailProcessedMessage g WHERE g.connection.id = :connectionId AND g.status = com.financeos.gmail.domain.GmailProcessedStatus.PROCESSING AND (g.processedAt <= :cutoff OR (g.processedAt IS NULL AND g.discoveredAt <= :cutoff))")
    List<GmailProcessedMessage> findStaleProcessing(@Param("connectionId") UUID connectionId, @Param("cutoff") Instant cutoff);

    @Query("SELECT g FROM GmailProcessedMessage g WHERE g.user.id = :userId AND g.status IN :statuses")
    Page<GmailProcessedMessage> findAttentionItems(@Param("userId") UUID userId, @Param("statuses") Collection<GmailProcessedStatus> statuses, Pageable pageable);

    @Query("SELECT g FROM GmailProcessedMessage g WHERE g.user.id = :userId AND g.extractedLast4 = :last4 AND g.status IN :statuses AND (g.internalDate IS NULL OR g.internalDate >= :minDate)")
    List<GmailProcessedMessage> findParkedForReactivation(@Param("userId") UUID userId, @Param("last4") String last4, @Param("statuses") Collection<GmailProcessedStatus> statuses, @Param("minDate") Instant minDate);

    Optional<GmailProcessedMessage> findByTransactionId(UUID transactionId);
}
