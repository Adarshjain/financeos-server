package com.financeos.sms.ingest;

import com.financeos.sms.read.SmsReadStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Every method is scoped to the user. */
public interface SmsMessageRepository extends JpaRepository<SmsMessage, UUID> {

    Optional<SmsMessage> findByIdAndUserId(UUID id, UUID userId);

    @Query("SELECT m.fingerprint FROM SmsMessage m WHERE m.userId = :userId AND m.fingerprint IN :fingerprints")
    Set<String> findKnownFingerprints(@Param("userId") UUID userId, @Param("fingerprints") Collection<String> fingerprints);

    List<SmsMessage> findByUserIdOrderByReceivedAtAscIdAsc(UUID userId);

    Page<SmsMessage> findByUserId(UUID userId, Pageable pageable);

    Page<SmsMessage> findByUserIdAndReadStatus(UUID userId, SmsReadStatus status, Pageable pageable);

    Page<SmsMessage> findByUserIdAndAccountId(UUID userId, UUID accountId, Pageable pageable);

    Page<SmsMessage> findByUserIdAndReadStatusAndAccountId(UUID userId, SmsReadStatus status, UUID accountId, Pageable pageable);

    /**
     * Kept messages (READ, decided, not themselves duplicates) of the account that could be the other alert of a message:
     * same reference number, or received in the window. Scalar columns only: id, receivedAt, refNo, templateKey, amount,
     * direction, balanceAfter, and how many duplicates already point at it.
     */
    @Query("SELECT m.id, m.receivedAt, m.refNo, m.templateKey, m.amount, m.direction, m.balanceAfter, "
            + "(SELECT COUNT(d.id) FROM SmsMessage d WHERE d.userId = :userId AND d.duplicateOf = m.id) "
            + "FROM SmsMessage m WHERE m.userId = :userId AND m.accountId = :accountId AND m.id <> :id "
            + "AND m.readStatus = com.financeos.sms.read.SmsReadStatus.READ AND m.outcome IS NOT NULL "
            + "AND m.outcome <> com.financeos.sms.ingest.SmsOutcome.DUPLICATE "
            + "AND (m.refNo = :refNo OR m.receivedAt BETWEEN :from AND :to)")
    List<Object[]> findDuplicateCandidates(@Param("userId") UUID userId, @Param("accountId") UUID accountId,
                                           @Param("id") UUID id, @Param("refNo") String refNo,
                                           @Param("from") Instant from, @Param("to") Instant to);

    /** True when another message of the user already claims this transaction (shadow or real). */
    boolean existsByUserIdAndTransactionIdAndIdNot(UUID userId, UUID transactionId, UUID id);

    /** The transactions other messages of the account received in the window already claim (shadow or real). */
    @Query("SELECT m.transactionId FROM SmsMessage m WHERE m.userId = :userId AND m.accountId = :accountId "
            + "AND m.id <> :id AND m.transactionId IS NOT NULL AND m.receivedAt BETWEEN :from AND :to")
    Set<UUID> findClaimedTransactionIds(@Param("userId") UUID userId, @Param("accountId") UUID accountId,
                                        @Param("id") UUID id, @Param("from") Instant from, @Param("to") Instant to);

    /** Ids of generic readings of an account with no outcome yet, received since the cutoff, newest first. */
    @Query("SELECT m.id FROM SmsMessage m WHERE m.userId = :userId AND m.accountId IS NOT NULL "
            + "AND m.readStatus = com.financeos.sms.read.SmsReadStatus.NEEDS_CONFIRM AND m.outcome IS NULL "
            + "AND m.receivedAt >= :since ORDER BY m.receivedAt DESC, m.id DESC")
    List<UUID> findUndecidedNeedsConfirmIds(@Param("userId") UUID userId, @Param("since") Instant since,
                                            org.springframework.data.domain.Pageable page);

    /** Clears outcome, duplicate and transaction link of every message that is not posted (CREATED / ATTACHED). */
    @org.springframework.data.jpa.repository.Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE SmsMessage m SET m.outcome = NULL, m.duplicateOf = NULL, m.transactionId = NULL WHERE m.userId = :userId "
            + "AND (m.outcome IS NULL OR m.outcome NOT IN (com.financeos.sms.ingest.SmsOutcome.CREATED, com.financeos.sms.ingest.SmsOutcome.ATTACHED))")
    int resetUnposted(@Param("userId") UUID userId);

    /** Ids of the user's messages without an outcome (after a reset: the unposted ones), in received order. */
    @Query("SELECT m.id FROM SmsMessage m WHERE m.userId = :userId AND m.outcome IS NULL ORDER BY m.receivedAt ASC, m.id ASC")
    List<UUID> findUnpostedIdsInReceivedOrder(@Param("userId") UUID userId);

    /** Messages of the user by outcome (null = no outcome). */
    @Query("SELECT m.outcome, COUNT(m) FROM SmsMessage m WHERE m.userId = :userId GROUP BY m.outcome")
    List<Object[]> outcomeCounts(@Param("userId") UUID userId);

    /** The newest stored message time per line. */
    @Query("SELECT m.lineId, MAX(m.receivedAt) FROM SmsMessage m WHERE m.userId = :userId AND m.lineId IS NOT NULL GROUP BY m.lineId")
    List<Object[]> newestPerLine(@Param("userId") UUID userId);

    /** Totals by read status and outcome per account (account null = the account is gone). */
    @Query("SELECT m.accountId, m.readStatus, m.outcome, COUNT(m), MIN(m.receivedAt), MAX(m.receivedAt) "
            + "FROM SmsMessage m WHERE m.userId = :userId GROUP BY m.accountId, m.readStatus, m.outcome")
    List<Object[]> reportCounts(@Param("userId") UUID userId);

    /** READ messages that are not duplicates, oldest first, for the balance-chain check (chains are cut per line group in code). */
    @Query("SELECT m.accountId, m.receivedAt, m.txnDate, m.amount, m.direction, m.balanceAfter, m.lineId FROM SmsMessage m "
            + "WHERE m.userId = :userId AND m.accountId IS NOT NULL "
            + "AND m.readStatus = com.financeos.sms.read.SmsReadStatus.READ "
            + "AND (m.outcome IS NULL OR m.outcome <> com.financeos.sms.ingest.SmsOutcome.DUPLICATE) "
            + "ORDER BY m.receivedAt, m.id")
    List<Object[]> findReadForBalanceChain(@Param("userId") UUID userId);

    /** Latest could-not-read message time per account. */
    @Query("SELECT m.accountId, MAX(m.receivedAt) FROM SmsMessage m WHERE m.userId = :userId "
            + "AND m.readStatus = com.financeos.sms.read.SmsReadStatus.COULD_NOT_READ GROUP BY m.accountId")
    List<Object[]> latestCouldNotRead(@Param("userId") UUID userId);
}
