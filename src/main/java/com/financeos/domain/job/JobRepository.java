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
    @Query("delete from JobArtifact a where a.jobId in (select j.id from Job j where j.status = 'FAILED' and j.finishedAt < :cutoff)")
    int deleteFailedJobArtifactsOlderThan(@Param("cutoff") Instant cutoff);

    @Modifying
    @Query("delete from Job j where j.status in :statuses and j.finishedAt < :cutoff")
    int deleteTerminalJobsOlderThan(@Param("statuses") Collection<JobStatus> statuses, @Param("cutoff") Instant cutoff);
}
