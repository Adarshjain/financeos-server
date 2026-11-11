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

    /** Totals by read status and outcome per account (account null = the account is gone). */
    @Query("SELECT m.accountId, m.readStatus, m.outcome, COUNT(m), MIN(m.receivedAt), MAX(m.receivedAt) "
            + "FROM SmsMessage m WHERE m.userId = :userId GROUP BY m.accountId, m.readStatus, m.outcome")
    List<Object[]> reportCounts(@Param("userId") UUID userId);

    /** READ messages that are not duplicates, oldest first within each line, for the balance-chain check. */
    @Query("SELECT m.accountId, m.receivedAt, m.txnDate, m.amount, m.direction, m.balanceAfter, m.lineId FROM SmsMessage m "
            + "WHERE m.userId = :userId AND m.accountId IS NOT NULL "
            + "AND m.readStatus = com.financeos.sms.read.SmsReadStatus.READ "
            + "AND (m.outcome IS NULL OR m.outcome <> com.financeos.sms.ingest.SmsOutcome.DUPLICATE) "
            + "ORDER BY m.lineId, m.receivedAt, m.id")
    List<Object[]> findReadForBalanceChain(@Param("userId") UUID userId);

    /** Latest could-not-read message time per account. */
    @Query("SELECT m.accountId, MAX(m.receivedAt) FROM SmsMessage m WHERE m.userId = :userId "
            + "AND m.readStatus = com.financeos.sms.read.SmsReadStatus.COULD_NOT_READ GROUP BY m.accountId")
    List<Object[]> latestCouldNotRead(@Param("userId") UUID userId);
}
