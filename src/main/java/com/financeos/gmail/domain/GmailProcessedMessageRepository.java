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
    /** One ledger row of this user with its mailbox loaded; empty for another user's row ("View email"). */
    @Query("SELECT g FROM GmailProcessedMessage g JOIN FETCH g.connection WHERE g.id = :id AND g.user.id = :userId")
    Optional<GmailProcessedMessage> findOwnedWithConnection(@Param("id") UUID id, @Param("userId") UUID userId);

    Optional<GmailProcessedMessage> findByConnectionIdAndGmailMessageId(UUID connectionId, String gmailMessageId);
    boolean existsByConnectionIdAndGmailMessageId(UUID connectionId, String gmailMessageId);

    /** Of these transactions, the ones an alert mail points at (a statement re-run leaves those rows linked). */
    @Query("SELECT DISTINCT g.transaction.id FROM GmailProcessedMessage g WHERE g.transaction.id IN :transactionIds")
    List<UUID> findTransactionIdsIn(@Param("transactionIds") Collection<UUID> transactionIds);

    /** An alert mail that points at a transaction: who sent it and when (alert matching). */
    interface AlertMail {
        UUID getTransactionId();
        String getGmailMessageId();
        String getSenderAddress();
        Instant getInternalDate();
    }

    /** The mails pointing at these transactions, one query (alert matching). */
    @Query("SELECT g.transaction.id AS transactionId, g.gmailMessageId AS gmailMessageId, g.senderAddress AS senderAddress, "
            + "g.internalDate AS internalDate FROM GmailProcessedMessage g WHERE g.transaction.id IN :transactionIds")
    List<AlertMail> findAlertMailsByTransactionIdIn(@Param("transactionIds") Collection<UUID> transactionIds);

    /** The mails pointing at any of these transactions, one query. */
    List<GmailProcessedMessage> findAllByTransactionIdIn(Collection<UUID> transactionIds);

    @Query("SELECT g.gmailMessageId FROM GmailProcessedMessage g WHERE g.connection.id = :connectionId AND g.gmailMessageId IN :gmailMessageIds")
    List<String> findExistingMessageIds(@Param("connectionId") UUID connectionId, @Param("gmailMessageIds") Collection<String> gmailMessageIds);

    /** A statement moved to another account: its mail (and its alert mails) are now that account's ledger entries. One update, scoped by user. */
    @org.springframework.data.jpa.repository.Modifying
    @Query("UPDATE GmailProcessedMessage g SET g.account = :to WHERE g.user.id = :userId AND g.account.id = :fromId AND g.gmailMessageId IN :messageIds")
    int moveToAccount(@Param("userId") UUID userId, @Param("fromId") UUID fromId,
                      @Param("to") com.financeos.domain.account.Account to, @Param("messageIds") Collection<String> messageIds);

    /** Admin Failures tool, across users: rows in these statuses with their user and mailbox, newest failure first; a projection (no error_detail CLOB). */
    @Query(value = "SELECT new com.financeos.gmail.domain.FailedGmailRow(g.id, g.gmailMessageId, u.id, u.email, c.email, g.senderAddress, g.subject, g.internalDate, "
            + "g.status, g.attemptCount, g.nextRetryAt, g.error, g.processedAt, g.dismissedAt, g.dismissedBy, g.dismissReason) "
            + "FROM GmailProcessedMessage g JOIN g.connection c JOIN g.user u WHERE g.status IN :statuses ORDER BY COALESCE(g.processedAt, g.discoveredAt) DESC, g.id DESC",
           countQuery = "SELECT COUNT(g) FROM GmailProcessedMessage g WHERE g.status IN :statuses")
    Page<FailedGmailRow> findForAdmin(@Param("statuses") Collection<GmailProcessedStatus> statuses, Pageable pageable);

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

    /**
     * The next rows to process: mails with an attachment (marked at discovery) or known statements first, so across a
     * whole backlog every statement lands before the alerts it covers; then by the mail's date, then discovery order.
     */
    @Query("SELECT g FROM GmailProcessedMessage g WHERE g.connection.id = :connectionId AND (g.status = com.financeos.gmail.domain.GmailProcessedStatus.DISCOVERED OR (g.status = com.financeos.gmail.domain.GmailProcessedStatus.FAILED_RETRYABLE AND g.nextRetryAt <= :now)) "
            + "ORDER BY CASE WHEN g.hasAttachment = true OR g.pipelineKind = 'STATEMENT' THEN 0 ELSE 1 END ASC, "
            + "CASE WHEN g.internalDate IS NULL THEN 1 ELSE 0 END ASC, g.internalDate ASC, g.discoveredAt ASC, g.id ASC")
    List<GmailProcessedMessage> findPendingForDrain(@Param("connectionId") UUID connectionId, @Param("now") Instant now, Pageable pageable);

    @Query("SELECT COUNT(g) FROM GmailProcessedMessage g WHERE g.connection.id = :connectionId AND (g.status = com.financeos.gmail.domain.GmailProcessedStatus.DISCOVERED OR (g.status = com.financeos.gmail.domain.GmailProcessedStatus.FAILED_RETRYABLE AND g.nextRetryAt <= :now))")
    long countDrainable(@Param("connectionId") UUID connectionId, @Param("now") Instant now);

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

    /** The mail of the unknown-number groups of one last 4 (same inclusion as {@link #findActiveUnresolvedNumbered}), with its mailbox; the caller picks the bank's group. */
    @Query("SELECT new com.financeos.gmail.domain.GmailAttentionRow(g, c.email, a.id, a.name) FROM GmailProcessedMessage g JOIN g.connection c LEFT JOIN g.account a WHERE g.user.id = :userId"
            + " AND g.status = com.financeos.gmail.domain.GmailProcessedStatus.UNRESOLVED_ACCOUNT AND g.extractedLast4 = :last4 AND g.archivedAt IS NULL" + NOT_STANDING_FOR_SUGGESTION)
    List<GmailAttentionRow> findActiveUnresolvedRowsByLast4(@Param("userId") UUID userId, @Param("last4") String last4);

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


    /** Inbox: every attention item for the user, notified or not; archived items and mail a NEW suggestion stands for are excluded. */
    @Query("SELECT COUNT(g) FROM GmailProcessedMessage g WHERE g.user.id = :userId AND g.status IN :statuses AND g.archivedAt IS NULL" + NOT_STANDING_FOR_SUGGESTION)
    long countByUserIdAndStatusIn(@Param("userId") UUID userId, @Param("statuses") Collection<GmailProcessedStatus> statuses);

    /** Attention items the user has not been told about yet (the GMAIL_ATTENTION digest input); archived items and mail a NEW suggestion stands for are excluded. */
    @Query("SELECT g FROM GmailProcessedMessage g WHERE g.user.id = :userId AND g.status IN :statuses AND g.attentionNotifiedAt IS NULL AND g.archivedAt IS NULL" + NOT_STANDING_FOR_SUGGESTION + " ORDER BY g.discoveredAt ASC")
    List<GmailProcessedMessage> findUnnotifiedAttentionItems(@Param("userId") UUID userId, @Param("statuses") Collection<GmailProcessedStatus> statuses);

    /** {@link #findAttentionItems}' rows for one account (the row's resolved account), same filter; archived excluded. */
    @Query("SELECT COUNT(g) FROM GmailProcessedMessage g WHERE g.user.id = :userId AND g.account.id = :accountId AND g.status IN :statuses AND g.archivedAt IS NULL")
    long countAttentionItemsForAccount(@Param("userId") UUID userId, @Param("accountId") UUID accountId, @Param("statuses") Collection<GmailProcessedStatus> statuses);

    /** Active attention items that belong to no group: {@link #findAttentionItems} minus unresolved mail that carries a number (those show grouped). */
    @Query(value = "SELECT new com.financeos.gmail.domain.GmailAttentionRow(g, c.email, a.id, a.name) FROM GmailProcessedMessage g JOIN g.connection c LEFT JOIN g.account a WHERE g.user.id = :userId AND g.status IN :statuses AND g.archivedAt IS NULL"
            + " AND NOT (g.status = com.financeos.gmail.domain.GmailProcessedStatus.UNRESOLVED_ACCOUNT AND g.extractedLast4 IS NOT NULL)" + NOT_STANDING_FOR_SUGGESTION,
           countQuery = "SELECT COUNT(g) FROM GmailProcessedMessage g WHERE g.user.id = :userId AND g.status IN :statuses AND g.archivedAt IS NULL"
            + " AND NOT (g.status = com.financeos.gmail.domain.GmailProcessedStatus.UNRESOLVED_ACCOUNT AND g.extractedLast4 IS NOT NULL)" + NOT_STANDING_FOR_SUGGESTION)
    Page<GmailAttentionRow> findUngroupedAttentionItems(@Param("userId") UUID userId, @Param("statuses") Collection<GmailProcessedStatus> statuses, Pageable pageable);

    /** Briefs of {@link #findUngroupedAttentionItems}' rows (same inclusion), for the ungrouped per-reason counts and bulk actions. */
    @Query("SELECT g.id AS id, g.status AS status, g.error AS error FROM GmailProcessedMessage g WHERE g.user.id = :userId AND g.status IN :statuses AND g.archivedAt IS NULL"
            + " AND NOT (g.status = com.financeos.gmail.domain.GmailProcessedStatus.UNRESOLVED_ACCOUNT AND g.extractedLast4 IS NOT NULL)" + NOT_STANDING_FOR_SUGGESTION)
    List<AttentionBrief> findUngroupedAttentionBriefs(@Param("userId") UUID userId, @Param("statuses") Collection<GmailProcessedStatus> statuses);

    /** Count of {@link #findUngroupedAttentionItems}' rows. */
    @Query("SELECT COUNT(g) FROM GmailProcessedMessage g WHERE g.user.id = :userId AND g.status IN :statuses AND g.archivedAt IS NULL"
            + " AND NOT (g.status = com.financeos.gmail.domain.GmailProcessedStatus.UNRESOLVED_ACCOUNT AND g.extractedLast4 IS NOT NULL)" + NOT_STANDING_FOR_SUGGESTION)
    long countUngroupedAttentionItems(@Param("userId") UUID userId, @Param("statuses") Collection<GmailProcessedStatus> statuses);

    /** What the "unknown number" groups, their suggestions and the seen dates need from an unresolved mail; no entities loaded. */
    interface NumberedMail {
        UUID getId();
        String getExtractedLast4();
        String getClassifiedBank();
        String getSenderAddress();
        Instant getInternalDate();
        Instant getDiscoveredAt();
        String getPipelineKind();
        String getClassifiedKind();
        String getError();
        java.math.BigDecimal getExtractAmount();
        String getExtractDirection();
        java.time.LocalDate getExtractDate();
        Instant getArchivedAt();
    }

    String NUMBERED_MAIL_SELECT = "SELECT g.id AS id, g.extractedLast4 AS extractedLast4, g.classifiedBank AS classifiedBank, g.senderAddress AS senderAddress, "
            + "g.internalDate AS internalDate, g.discoveredAt AS discoveredAt, g.pipelineKind AS pipelineKind, g.classifiedKind AS classifiedKind, g.error AS error, "
            + "g.extractAmount AS extractAmount, g.extractDirection AS extractDirection, g.extractDate AS extractDate, g.archivedAt AS archivedAt FROM GmailProcessedMessage g ";

    /** Active unresolved mail that carries a number: the input of the unknown-number groups (same inclusion as the list). */
    @Query(NUMBERED_MAIL_SELECT + "WHERE g.user.id = :userId AND g.status = com.financeos.gmail.domain.GmailProcessedStatus.UNRESOLVED_ACCOUNT AND g.extractedLast4 IS NOT NULL AND g.archivedAt IS NULL" + NOT_STANDING_FOR_SUGGESTION)
    List<NumberedMail> findActiveUnresolvedNumbered(@Param("userId") UUID userId);

    /** Archived unresolved mail that carries a number (counts for the ignored numbers). */
    @Query(NUMBERED_MAIL_SELECT + "WHERE g.user.id = :userId AND g.status = com.financeos.gmail.domain.GmailProcessedStatus.UNRESOLVED_ACCOUNT AND g.extractedLast4 IS NOT NULL AND g.archivedAt IS NOT NULL")
    List<NumberedMail> findArchivedUnresolvedNumbered(@Param("userId") UUID userId);

    /** Mail the extractor or the classifier tied to this last 4, any status: the seen dates of a number. */
    @Query(NUMBERED_MAIL_SELECT + "WHERE g.user.id = :userId AND (g.extractedLast4 = :last4 OR g.extractLast4 = :last4)")
    List<NumberedMail> findMentioning(@Param("userId") UUID userId, @Param("last4") String last4);

    /** One account's not-opted-in mail count and span, open accounts only (a closed account's mail is skipped, not listed). */
    interface ImportOffAccount {
        UUID getAccountId();
        String getAccountName();
        long getTotal();
        Instant getFirstMail();
        Instant getLastMail();
    }

    @Query("SELECT a.id AS accountId, a.name AS accountName, COUNT(g) AS total, MIN(COALESCE(g.internalDate, g.discoveredAt)) AS firstMail, "
            + "MAX(COALESCE(g.internalDate, g.discoveredAt)) AS lastMail FROM GmailProcessedMessage g JOIN g.account a "
            + "WHERE g.user.id = :userId AND g.status = com.financeos.gmail.domain.GmailProcessedStatus.ACCOUNT_NOT_OPTED_IN "
            + "AND (a.closedOn IS NULL OR a.closedOn > :today) GROUP BY a.id, a.name ORDER BY MAX(COALESCE(g.internalDate, g.discoveredAt)) DESC")
    List<ImportOffAccount> findImportOffAccounts(@Param("userId") UUID userId, @Param("today") java.time.LocalDate today);
}
