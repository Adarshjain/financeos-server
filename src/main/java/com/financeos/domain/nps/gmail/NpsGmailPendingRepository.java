package com.financeos.domain.nps.gmail;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface NpsGmailPendingRepository extends JpaRepository<NpsGmailPending, UUID> {

    /** The user's held rows of one status, oldest first (a few rows, loaded in one query). */
    @Query("SELECT p FROM NpsGmailPending p WHERE p.user.id = :userId AND p.status = :status "
            + "ORDER BY p.periodEnd, p.createdAt, p.id")
    List<NpsGmailPending> findByStatus(@Param("userId") UUID userId, @Param("status") NpsGmailPendingStatus status);

    /** The user's rows of one reason and status (PASSWORD rows to unlock, NEW_ACCOUNT rows to group by PRAN). */
    @Query("SELECT p FROM NpsGmailPending p WHERE p.user.id = :userId AND p.reason = :reason AND p.status = :status "
            + "ORDER BY p.periodEnd, p.createdAt, p.id")
    List<NpsGmailPending> findByReasonAndStatus(@Param("userId") UUID userId, @Param("reason") NpsGmailPendingReason reason,
                                                @Param("status") NpsGmailPendingStatus status);

    /** The user's PENDING password-locked rows, never-tried first then least recently tried (failed rows rotate to the back). */
    @Query("SELECT p FROM NpsGmailPending p WHERE p.user.id = :userId AND p.reason = "
            + "com.financeos.domain.nps.gmail.NpsGmailPendingReason.PASSWORD AND p.status = "
            + "com.financeos.domain.nps.gmail.NpsGmailPendingStatus.PENDING "
            + "ORDER BY p.lastTriedAt ASC NULLS FIRST, p.periodEnd, p.createdAt, p.id")
    List<NpsGmailPending> findLockedForUnlock(@Param("userId") UUID userId);

    /** The user's NEW_ACCOUNT rows (their PRAN is held) in any of these statuses, oldest period first. */
    @Query("SELECT p FROM NpsGmailPending p WHERE p.user.id = :userId AND p.reason = "
            + "com.financeos.domain.nps.gmail.NpsGmailPendingReason.NEW_ACCOUNT AND p.status IN :statuses "
            + "ORDER BY p.periodEnd, p.createdAt, p.id")
    List<NpsGmailPending> findHeld(@Param("userId") UUID userId, @Param("statuses") Collection<NpsGmailPendingStatus> statuses);

    @Query("SELECT p FROM NpsGmailPending p WHERE p.user.id = :userId AND p.gmailMessageId = :messageId")
    Optional<NpsGmailPending> findByUserIdAndMessageId(@Param("userId") UUID userId, @Param("messageId") String messageId);
}
