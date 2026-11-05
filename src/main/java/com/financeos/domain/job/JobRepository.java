package com.financeos.domain.job;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface JobRepository extends JpaRepository<Job, UUID> {

    Page<Job> findByUserId(UUID userId, Pageable pageable);

    Page<Job> findByUserIdAndStatusIn(UUID userId, Collection<JobStatus> statuses, Pageable pageable);

    Page<Job> findByUserIdAndTypeIn(UUID userId, Collection<JobType> types, Pageable pageable);

    Page<Job> findByUserIdAndTypeInAndStatusIn(UUID userId, Collection<JobType> types, Collection<JobStatus> statuses, Pageable pageable);

    Page<Job> findByUserIdAndCreatedAtGreaterThanEqual(UUID userId, Instant since, Pageable pageable);

    Page<Job> findByUserIdAndStatusInAndCreatedAtGreaterThanEqual(UUID userId, Collection<JobStatus> statuses, Instant since, Pageable pageable);

    Page<Job> findByUserIdAndTypeInAndCreatedAtGreaterThanEqual(UUID userId, Collection<JobType> types, Instant since, Pageable pageable);

    Page<Job> findByUserIdAndTypeInAndStatusInAndCreatedAtGreaterThanEqual(UUID userId, Collection<JobType> types, Collection<JobStatus> statuses, Instant since, Pageable pageable);

    /** The user's most recent finished job of this type at or before {@code finishedAt}, excluding {@code excludeId} (use a page of 1). */
    @Query("select j from Job j where j.userId = :userId and j.type = :type and j.id <> :excludeId and j.status in :statuses "
            + "and j.finishedAt is not null and j.finishedAt <= :finishedAt order by j.finishedAt desc")
    List<Job> findPreviousFinished(@Param("userId") UUID userId, @Param("type") JobType type, @Param("excludeId") UUID excludeId,
                                   @Param("statuses") Collection<JobStatus> statuses, @Param("finishedAt") Instant finishedAt, Pageable limit);

    /** The email of a user (for the "dismissed by" mark of a user retry); null when unknown. */
    @Query("select u.email from User u where u.id = :userId")
    String findUserEmail(@Param("userId") UUID userId);

    String FAILED_ROW = "select new com.financeos.domain.job.FailedJobRow(j.id, j.type, j.userId, u.email, j.errorCode, j.errorMessage, j.attempt, "
            + "j.startedAt, j.finishedAt, j.dismissedAt, j.dismissedBy, j.dismissReason) from Job j left join User u on u.id = j.userId "
            + "where j.status = com.financeos.domain.job.JobStatus.FAILED and j.finishedAt >= :since";
    String FAILED_COUNT = "select count(j) from Job j where j.status = com.financeos.domain.job.JobStatus.FAILED and j.finishedAt >= :since";

    /**
     * Admin Failures tool, across users: FAILED jobs finished since {@code since}, newest first, dismissed ones included.
     * A projection: the stack (error_detail CLOB) is not read; the owner's email comes in the same query.
     */
    @Query(value = FAILED_ROW + " order by j.finishedAt desc, j.id desc", countQuery = FAILED_COUNT)
    Page<FailedJobRow> findFailedWithDismissedForAdmin(@Param("since") Instant since, Pageable pageable);

    /** As {@link #findFailedWithDismissedForAdmin} but only the non-dismissed ones. */
    @Query(value = FAILED_ROW + " and j.dismissedAt is null order by j.finishedAt desc, j.id desc", countQuery = FAILED_COUNT + " and j.dismissedAt is null")
    Page<FailedJobRow> findFailedForAdmin(@Param("since") Instant since, Pageable pageable);

    /** Every non-dismissed FAILED job finished since {@code since}, newest first (retry-all); identity columns only. */
    @Query("select new com.financeos.domain.job.JobKeyRow(j.id, j.userId, j.type, j.payload, j.finishedAt) from Job j "
            + "where j.status = com.financeos.domain.job.JobStatus.FAILED and j.finishedAt >= :since and j.dismissedAt is null order by j.finishedAt desc, j.id desc")
    List<JobKeyRow> findFailedActiveForAdmin(@Param("since") Instant since);

    /**
     * SUCCEEDED jobs since {@code since} of a type and owner that has a FAILED, non-dismissed job of the same type finished
     * before it (retry-all: a later success supersedes the failure). The payload is matched by the caller.
     */
    @Query("select new com.financeos.domain.job.JobKeyRow(s.id, s.userId, s.type, s.payload, s.finishedAt) from Job s "
            + "where s.status = com.financeos.domain.job.JobStatus.SUCCEEDED and s.finishedAt >= :since and exists ("
            + "select 1 from Job f where f.status = com.financeos.domain.job.JobStatus.FAILED and f.dismissedAt is null and f.finishedAt >= :since "
            + "and f.type = s.type and f.finishedAt <= s.finishedAt and ((f.userId is null and s.userId is null) or f.userId = s.userId))")
    List<JobKeyRow> findSucceededAfterFailuresForAdmin(@Param("since") Instant since);

    /** Marks these jobs dismissed in one statement (retry-all supersede); already dismissed ones are left alone. */
    @Modifying
    @Query("update Job j set j.dismissedAt = :now, j.dismissedBy = :by, j.dismissReason = :reason where j.id in :ids and j.dismissedAt is null")
    int dismissAll(@Param("ids") Collection<UUID> ids, @Param("now") Instant now, @Param("by") String by, @Param("reason") String reason);

    @Query("select count(j) from Job j where j.status = com.financeos.domain.job.JobStatus.FAILED and j.finishedAt >= :since and j.dismissedAt is null")
    long countFailedActiveForAdmin(@Param("since") Instant since);

    Optional<Job> findByIdAndUserId(UUID id, UUID userId);

    long countByUserIdAndStatus(UUID userId, JobStatus status);

    @Modifying
    @Query("update Job j set j.cancelRequested = true, j.updatedAt = :now where j.userId = :userId and j.status in :statuses")
    int requestCancelForUserJobs(@Param("userId") UUID userId, @Param("statuses") Collection<JobStatus> statuses, @Param("now") Instant now);

    @Query("select j from Job j where (:userId is null and j.userId is null or j.userId = :userId) and j.type = :type and j.dedupKey = :dedupKey and j.status in :statuses")
    Optional<Job> findActiveDuplicate(@Param("userId") UUID userId, @Param("type") JobType type, @Param("dedupKey") String dedupKey, @Param("statuses") Collection<JobStatus> statuses);

    List<Job> findByStatusOrderByCreatedAtAsc(JobStatus status, Pageable limit);

    @Modifying
    @Query("update Job j set j.status = 'RUNNING', j.startedAt = :startedAt, j.attempt = j.attempt + 1 where j.id = :id and j.status = 'PENDING'")
    int claim(@Param("id") UUID id, @Param("startedAt") Instant startedAt);

    @Modifying
    @Query("update Job j set j.status = :toStatus, j.startedAt = :startedAt, j.attempt = j.attempt + 1 where j.id = :id and j.status = :fromStatus")
    int claim(@Param("id") UUID id, @Param("fromStatus") JobStatus fromStatus, @Param("toStatus") JobStatus toStatus, @Param("startedAt") Instant startedAt);

    @Modifying
    @Query("update Job j set j.status = :toStatus, j.errorCode = :errorCode, j.errorMessage = :errorMessage, j.finishedAt = :finishedAt where j.status = :fromStatus")
    int markInterrupted(@Param("fromStatus") JobStatus fromStatus, @Param("toStatus") JobStatus toStatus, @Param("errorCode") String errorCode, @Param("errorMessage") String errorMessage, @Param("finishedAt") Instant finishedAt);

    @Modifying
    @Query("delete from JobArtifact a where a.retained = false and a.jobId in (select j.id from Job j where j.status = 'FAILED' and j.finishedAt < :cutoff)")
    int deleteFailedJobArtifactsOlderThan(@Param("cutoff") Instant cutoff);

    @Modifying
    @Query("delete from Job j where j.status in :statuses and j.finishedAt < :cutoff")
    int deleteTerminalJobsOlderThan(@Param("statuses") Collection<JobStatus> statuses, @Param("cutoff") Instant cutoff);
}
