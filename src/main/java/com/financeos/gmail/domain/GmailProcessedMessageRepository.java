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

    /** A statement moved to another account: its mail (and its alert mails) are now that account's ledger entries. One update, scoped by user. */
    @org.springframework.data.jpa.repository.Modifying
    @Query("UPDATE GmailProcessedMessage g SET g.account = :to WHERE g.user.id = :userId AND g.account.id = :fromId AND g.gmailMessageId IN :messageIds")
    int moveToAccount(@Param("userId") UUID userId, @Param("fromId") UUID fromId,
                      @Param("to") com.financeos.domain.account.Account to, @Param("messageIds") Collection<String> messageIds);

    /** Admin Failures tool, across users: rows in these statuses with their user and mailbox, newest failure first. */
    @Query(value = "SELECT g FROM GmailProcessedMessage g JOIN FETCH g.connection JOIN FETCH g.user WHERE g.status IN :statuses ORDER BY COALESCE(g.processedAt, g.discoveredAt) DESC, g.id DESC",
           countQuery = "SELECT COUNT(g) FROM GmailProcessedMessage g WHERE g.status IN :statuses")
    Page<GmailProcessedMessage> findForAdmin(@Param("statuses") Collection<GmailProcessedStatus> statuses, Pageable pageable);

    long countByStatusAndDismissedAtIsNull(GmailProcessedStatus status);

    /** The (user, mailbox) pairs that have rows in these statuses, one sync per pair after a bulk reset. */
    @Query("SELECT DISTINCT g.user.id, g.connection.id FROM GmailProcessedMessage g WHERE g.status IN :statuses")
    List<Object[]> findUserConnectionPairsByStatusIn(@Param("statuses") Collection<GmailProcessedStatus> statuses);

    /** Bulk reset of failed rows to DISCOVERED (the same fields as {@link GmailProcessedMessage#resetForRetry}), one statement. */
    @org.springframework.data.jpa.repository.Modifying
    @Query("UPDATE GmailProcessedMessage g SET g.status = com.financeos.gmail.domain.GmailProcessedStatus.DISCOVERED, g.attemptCount = 0, g.nextRetryAt = NULL, g.error = NULL, g.errorDetail = NULL, g.dismissedAt = NULL, g.dismissedBy = NULL, g.dismissReason = NULL, g.attentionNotifiedAt = NULL, g.archivedAt = NULL WHERE g.status IN :statuses")
    int resetFailedToDiscovered(@Param("statuses") Collection<GmailProcessedStatus> statuses);

    long countByConnectionIdAndStatus(UUID connectionId, GmailProcessedStatus status);

    long countByConnectionIdAndStatusIn(UUID connectionId, Collection<GmailProcessedStatus> statuses);

    long countByUserIdAndStatus(UUID userId, GmailProcessedStatus status);

    @Query("SELECT g FROM GmailProcessedMessage g WHERE g.connection.id = :connectionId AND (g.status = com.financeos.gmail.domain.GmailProcessedStatus.DISCOVERED OR (g.status = com.financeos.gmail.domain.GmailProcessedStatus.FAILED_RETRYABLE AND g.nextRetryAt <= :now)) ORDER BY g.discoveredAt ASC, g.id ASC")
    List<GmailProcessedMessage> findPendingForDrain(@Param("connectionId") UUID connectionId, @Param("now") Instant now, Pageable pageable);

    @Query("SELECT g FROM GmailProcessedMessage g WHERE g.connection.id = :connectionId AND g.status = com.financeos.gmail.domain.GmailProcessedStatus.PROCESSING AND (g.processedAt <= :cutoff OR (g.processedAt IS NULL AND g.discoveredAt <= :cutoff))")
    List<GmailProcessedMessage> findStaleProcessing(@Param("connectionId") UUID connectionId, @Param("cutoff") Instant cutoff);

    /**
     * Inbox rows a NEW account suggestion stands for: an UNRESOLVED_ACCOUNT mail whose last4 (and bank, when the
     * classifier named one) is that of one of the user's NEW suggestions. With no bank on the mail, the last4 alone counts
     * only when exactly one NEW suggestion has it. One subquery per statement, never a query per row. Append to a WHERE
     * clause over {@code g}.
     */
    String NOT_STANDING_FOR_SUGGESTION = " AND NOT (g.status = com.financeos.gmail.domain.GmailProcessedStatus.UNRESOLVED_ACCOUNT"
            + " AND g.extractedLast4 IS NOT NULL AND EXISTS (SELECT 1 FROM AccountSuggestion sg WHERE sg.userId = :userId"
            + " AND sg.state = com.financeos.gmail.accountdetect.suggest.AccountSuggestion.State.NEW AND sg.last4 = g.extractedLast4"
            + " AND ((g.classifiedBank IS NOT NULL AND sg.bank = g.classifiedBank) OR (g.classifiedBank IS NULL AND"
            + " (SELECT COUNT(sg2) FROM AccountSuggestion sg2 WHERE sg2.userId = :userId"
            + " AND sg2.state = com.financeos.gmail.accountdetect.suggest.AccountSuggestion.State.NEW AND sg2.last4 = g.extractedLast4) = 1))))";

    /** Active (not archived) attention items, with the mailbox email and account name for the list's detail fields. */
    @Query(value = "SELECT new com.financeos.gmail.domain.GmailAttentionRow(g, c.email, a.id, a.name) FROM GmailProcessedMessage g JOIN g.connection c LEFT JOIN g.account a WHERE g.user.id = :userId AND g.status IN :statuses AND g.archivedAt IS NULL" + NOT_STANDING_FOR_SUGGESTION,
           countQuery = "SELECT COUNT(g) FROM GmailProcessedMessage g WHERE g.user.id = :userId AND g.status IN :statuses AND g.archivedAt IS NULL" + NOT_STANDING_FOR_SUGGESTION)
    Page<GmailAttentionRow> findAttentionItems(@Param("userId") UUID userId, @Param("statuses") Collection<GmailProcessedStatus> statuses, Pageable pageable);

    /** Id, status and error of a row: enough to classify its {@link AttentionReason} without loading entities. */
    interface AttentionBrief {
        UUID getId();
        GmailProcessedStatus getStatus();
        String getError();
    }

    /** Active attention rows (same inclusion as {@link #findAttentionItems}) as briefs, for the summary and bulk actions. */
    @Query("SELECT g.id AS id, g.status AS status, g.error AS error FROM GmailProcessedMessage g WHERE g.user.id = :userId AND g.status IN :statuses AND g.archivedAt IS NULL" + NOT_STANDING_FOR_SUGGESTION)
    List<AttentionBrief> findAttentionBriefs(@Param("userId") UUID userId, @Param("statuses") Collection<GmailProcessedStatus> statuses);

    /** Rows by id, scoped to one user. */
    @Query("SELECT g FROM GmailProcessedMessage g WHERE g.user.id = :userId AND g.id IN :ids")
    List<GmailProcessedMessage> findByUserIdAndIdIn(@Param("userId") UUID userId, @Param("ids") Collection<UUID> ids);

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

    /** Inbox: every attention item for the user, notified or not; archived items and mail a NEW suggestion stands for are excluded. */
    @Query("SELECT COUNT(g) FROM GmailProcessedMessage g WHERE g.user.id = :userId AND g.status IN :statuses AND g.archivedAt IS NULL" + NOT_STANDING_FOR_SUGGESTION)
    long countByUserIdAndStatusIn(@Param("userId") UUID userId, @Param("statuses") Collection<GmailProcessedStatus> statuses);

    /** Attention items the user has not been told about yet (the GMAIL_ATTENTION digest input); archived items and mail a NEW suggestion stands for are excluded. */
    @Query("SELECT g FROM GmailProcessedMessage g WHERE g.user.id = :userId AND g.status IN :statuses AND g.attentionNotifiedAt IS NULL AND g.archivedAt IS NULL" + NOT_STANDING_FOR_SUGGESTION + " ORDER BY g.discoveredAt ASC")
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
