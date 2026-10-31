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

    /** Active (not archived) attention items, with the mailbox email and account name for the list's detail fields. */
    @Query(value = "SELECT new com.financeos.gmail.domain.GmailAttentionRow(g, c.email, a.id, a.name) FROM GmailProcessedMessage g JOIN g.connection c LEFT JOIN g.account a WHERE g.user.id = :userId AND g.status IN :statuses AND g.archivedAt IS NULL",
           countQuery = "SELECT COUNT(g) FROM GmailProcessedMessage g WHERE g.user.id = :userId AND g.status IN :statuses AND g.archivedAt IS NULL")
    Page<GmailAttentionRow> findAttentionItems(@Param("userId") UUID userId, @Param("statuses") Collection<GmailProcessedStatus> statuses, Pageable pageable);

    /** Attention items the user archived, same shape as {@link #findAttentionItems}. */
    @Query(value = "SELECT new com.financeos.gmail.domain.GmailAttentionRow(g, c.email, a.id, a.name) FROM GmailProcessedMessage g JOIN g.connection c LEFT JOIN g.account a WHERE g.user.id = :userId AND g.status IN :statuses AND g.archivedAt IS NOT NULL",
           countQuery = "SELECT COUNT(g) FROM GmailProcessedMessage g WHERE g.user.id = :userId AND g.status IN :statuses AND g.archivedAt IS NOT NULL")
    Page<GmailAttentionRow> findArchivedAttentionItems(@Param("userId") UUID userId, @Param("statuses") Collection<GmailProcessedStatus> statuses, Pageable pageable);

    @Query("SELECT g FROM GmailProcessedMessage g WHERE g.user.id = :userId AND g.extractedLast4 = :last4 AND g.status IN :statuses AND (g.internalDate IS NULL OR g.internalDate >= :minDate)")
    List<GmailProcessedMessage> findParkedForReactivation(@Param("userId") UUID userId, @Param("last4") String last4, @Param("statuses") Collection<GmailProcessedStatus> statuses, @Param("minDate") Instant minDate);

    /** Parked rows for a last 4 whose email predates {@code before}: outside the window an ingest date reopens. */
    @Query("SELECT g FROM GmailProcessedMessage g WHERE g.user.id = :userId AND g.extractedLast4 = :last4 AND g.status IN :statuses AND g.internalDate < :before")
    List<GmailProcessedMessage> findParkedBefore(@Param("userId") UUID userId, @Param("last4") String last4, @Param("statuses") Collection<GmailProcessedStatus> statuses, @Param("before") Instant before);

    @Query("SELECT g FROM GmailProcessedMessage g WHERE g.user.id = :userId AND g.extractedLast4 = :last4 AND g.status IN :statuses")
    List<GmailProcessedMessage> findParked(@Param("userId") UUID userId, @Param("last4") String last4, @Param("statuses") Collection<GmailProcessedStatus> statuses);

    @Query("SELECT DISTINCT g.extractedLast4 FROM GmailProcessedMessage g WHERE g.user.id = :userId AND g.extractedLast4 IS NOT NULL AND g.status IN :statuses")
    List<String> findParkedLast4s(@Param("userId") UUID userId, @Param("statuses") Collection<GmailProcessedStatus> statuses);

    Optional<GmailProcessedMessage> findByTransactionId(UUID transactionId);

    /** Inbox: every attention item for the user, notified or not; archived items are excluded. */
    @Query("SELECT COUNT(g) FROM GmailProcessedMessage g WHERE g.user.id = :userId AND g.status IN :statuses AND g.archivedAt IS NULL")
    long countByUserIdAndStatusIn(@Param("userId") UUID userId, @Param("statuses") Collection<GmailProcessedStatus> statuses);

    /** Attention items the user has not been told about yet (the GMAIL_ATTENTION digest input); archived items are excluded. */
    @Query("SELECT g FROM GmailProcessedMessage g WHERE g.user.id = :userId AND g.status IN :statuses AND g.attentionNotifiedAt IS NULL AND g.archivedAt IS NULL ORDER BY g.discoveredAt ASC")
    List<GmailProcessedMessage> findUnnotifiedAttentionItems(@Param("userId") UUID userId, @Param("statuses") Collection<GmailProcessedStatus> statuses);

    /** {@link #findAttentionItems}' rows for one account (the row's resolved account), same filter; archived excluded. */
    @Query("SELECT COUNT(g) FROM GmailProcessedMessage g WHERE g.user.id = :userId AND g.account.id = :accountId AND g.status IN :statuses AND g.archivedAt IS NULL")
    long countAttentionItemsForAccount(@Param("userId") UUID userId, @Param("accountId") UUID accountId, @Param("statuses") Collection<GmailProcessedStatus> statuses);

    /** A Gmail-sourced transaction found by the shadow merge check, with the sender of the mail that made it. */
    interface MergeCandidate {
        UUID getTransactionId();
        String getSenderAddress();
    }

    /** Twin candidates for the shadow merge check, any account of the user; one query, no per-row loads. */
    @Query("SELECT g.transaction.id AS transactionId, g.senderAddress AS senderAddress FROM GmailProcessedMessage g " +
           "WHERE g.user.id = :userId AND g.transaction IS NOT NULL AND g.gmailMessageId <> :gmailMessageId " +
           "AND g.transaction.amount = :amount AND g.transaction.type = :type " +
           "AND g.transaction.date BETWEEN :fromDate AND :toDate ORDER BY g.transaction.date ASC, g.transaction.id ASC")
    List<MergeCandidate> findMergeCandidates(@Param("userId") UUID userId, @Param("gmailMessageId") String gmailMessageId,
                                             @Param("amount") java.math.BigDecimal amount,
                                             @Param("type") com.financeos.domain.transaction.TransactionType type,
                                             @Param("fromDate") java.time.LocalDate fromDate,
                                             @Param("toDate") java.time.LocalDate toDate);

    /** {@link #findMergeCandidates} limited to one account. */
    @Query("SELECT g.transaction.id AS transactionId, g.senderAddress AS senderAddress FROM GmailProcessedMessage g " +
           "WHERE g.user.id = :userId AND g.transaction IS NOT NULL AND g.gmailMessageId <> :gmailMessageId " +
           "AND g.transaction.account.id = :accountId " +
           "AND g.transaction.amount = :amount AND g.transaction.type = :type " +
           "AND g.transaction.date BETWEEN :fromDate AND :toDate ORDER BY g.transaction.date ASC, g.transaction.id ASC")
    List<MergeCandidate> findMergeCandidatesForAccount(@Param("userId") UUID userId, @Param("accountId") UUID accountId,
                                                       @Param("gmailMessageId") String gmailMessageId,
                                                       @Param("amount") java.math.BigDecimal amount,
                                                       @Param("type") com.financeos.domain.transaction.TransactionType type,
                                                       @Param("fromDate") java.time.LocalDate fromDate,
                                                       @Param("toDate") java.time.LocalDate toDate);
}
