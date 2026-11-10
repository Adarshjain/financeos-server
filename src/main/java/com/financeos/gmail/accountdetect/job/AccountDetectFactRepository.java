package com.financeos.gmail.accountdetect.job;

import jakarta.persistence.QueryHint;
import org.hibernate.jpa.HibernateHints;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/** Every query names the user explicitly (no Hibernate user filter inside jobs). */
@Repository
public interface AccountDetectFactRepository extends JpaRepository<AccountDetectFact, UUID> {

    /** All of one user's facts in a stable order: internal date, then message id. */
    @QueryHints({@QueryHint(name = HibernateHints.HINT_FETCH_SIZE, value = "500"), @QueryHint(name = HibernateHints.HINT_READ_ONLY, value = "true")})
    @Query("select f from AccountDetectFact f where f.userId = :userId order by f.internalDate asc nulls first, f.messageId asc")
    List<AccountDetectFact> findAllForUser(@Param("userId") UUID userId);

    /** The user's facts for these message ids (at most 1000 per call: Oracle's IN-list limit), in the same order as {@link #findAllForUser}. */
    @QueryHints({@QueryHint(name = HibernateHints.HINT_FETCH_SIZE, value = "500"), @QueryHint(name = HibernateHints.HINT_READ_ONLY, value = "true")})
    @Query("select f from AccountDetectFact f where f.userId = :userId and f.messageId in :messageIds order by f.internalDate asc nulls first, f.messageId asc")
    List<AccountDetectFact> findForUserAndMessageIds(@Param("userId") UUID userId, @Param("messageIds") java.util.Collection<String> messageIds);

    @Query("select f.messageId from AccountDetectFact f where f.userId = :userId")
    List<String> findMessageIdsForUser(@Param("userId") UUID userId);

    /** The user's mails whose protected statement PDF is still closed (never opened, or tried and failed). */
    @Query("select f from AccountDetectFact f where f.userId = :userId and f.unlockState in (com.financeos.gmail.accountdetect.job.AccountDetectFact.UnlockState.LOCKED, com.financeos.gmail.accountdetect.job.AccountDetectFact.UnlockState.FAILED) and f.pdfAttachmentId is not null and f.unlockTriedGen < :gen order by f.internalDate desc nulls last, f.messageId asc")
    List<AccountDetectFact> findStillLockedForUser(@Param("userId") UUID userId, @Param("gen") int gen);

    /** The user's mails with a statement PDF that opened (without or with a rule): their holder flag can be recomputed. */
    @Query("select f from AccountDetectFact f where f.userId = :userId and f.hasPdf = true and f.unlockState in (com.financeos.gmail.accountdetect.job.AccountDetectFact.UnlockState.NONE, com.financeos.gmail.accountdetect.job.AccountDetectFact.UnlockState.UNLOCKED) and f.pdfAttachmentId is not null and f.holderCheckedGen < :gen order by f.internalDate desc nulls last, f.messageId asc")
    List<AccountDetectFact> findOpenedPdfForUser(@Param("userId") UUID userId, @Param("gen") int gen);

    /** Bookkeeping of the user's open or unlocked statement PDFs, newest first; the encrypted facts column is not selected. */
    @Query("select new com.financeos.gmail.accountdetect.job.AccountDetectStore$PdfRow(f.messageId, f.internalDate, f.pdfAttachmentId, f.unlockState) from AccountDetectFact f where f.userId = :userId and f.hasPdf = true and f.unlockState in (com.financeos.gmail.accountdetect.job.AccountDetectFact.UnlockState.NONE, com.financeos.gmail.accountdetect.job.AccountDetectFact.UnlockState.UNLOCKED) and f.pdfAttachmentId is not null order by f.internalDate desc nulls last, f.messageId asc")
    List<AccountDetectStore.PdfRow> findPdfRowsForUser(@Param("userId") UUID userId);

    /** Bookkeeping of the user's statement PDFs that are still closed (LOCKED or FAILED), newest first. */
    @Query("select new com.financeos.gmail.accountdetect.job.AccountDetectStore$PdfRow(f.messageId, f.internalDate, f.pdfAttachmentId, f.unlockState) from AccountDetectFact f where f.userId = :userId and f.hasPdf = true and f.unlockState in (com.financeos.gmail.accountdetect.job.AccountDetectFact.UnlockState.LOCKED, com.financeos.gmail.accountdetect.job.AccountDetectFact.UnlockState.FAILED) and f.pdfAttachmentId is not null order by f.internalDate desc nulls last, f.messageId asc")
    List<AccountDetectStore.PdfRow> findClosedPdfRowsForUser(@Param("userId") UUID userId);

    /** Facts a retry pass still has to look at: locked ones not tried at the current unlock generation, opened ones not rechecked at the holder generation. */
    @Query("select count(f) from AccountDetectFact f, AccountDetectState s where s.userId = :userId and f.userId = s.userId and f.pdfAttachmentId is not null and ("
            + "(f.unlockState in (com.financeos.gmail.accountdetect.job.AccountDetectFact.UnlockState.LOCKED, com.financeos.gmail.accountdetect.job.AccountDetectFact.UnlockState.FAILED) and f.unlockTriedGen < s.unlockGen) or "
            + "(f.hasPdf = true and f.unlockState in (com.financeos.gmail.accountdetect.job.AccountDetectFact.UnlockState.NONE, com.financeos.gmail.accountdetect.job.AccountDetectFact.UnlockState.UNLOCKED) and f.holderCheckedGen < s.holderGen))")
    long countPendingForUser(@Param("userId") UUID userId);

    @Query("select f from AccountDetectFact f where f.userId = :userId and f.messageId = :messageId")
    java.util.Optional<AccountDetectFact> findByUserAndMessage(@Param("userId") UUID userId, @Param("messageId") String messageId);

    @Modifying
    @Query("delete from AccountDetectFact f where f.userId = :userId")
    int deleteForUser(@Param("userId") UUID userId);

    /** Drops these rows of the user (a partial mail about to be stored again). */
    @Modifying
    @Query("delete from AccountDetectFact f where f.userId = :userId and f.messageId in :messageIds")
    int deleteForUserAndMessageIds(@Param("userId") UUID userId, @Param("messageIds") java.util.Collection<String> messageIds);
}
