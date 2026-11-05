package com.financeos.domain.job;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface JobArtifactRepository extends JpaRepository<JobArtifact, UUID> {
    List<JobArtifact> findByJobId(UUID jobId);
    void deleteByJobId(UUID jobId);

    /** What a succeeded job drops: everything except the files kept (held uploads, password retries). */
    void deleteByJobIdAndRetainedFalse(UUID jobId);

    /** Which of these jobs still have stored files (one query for a whole page, never one per job). */
    @org.springframework.data.jpa.repository.Query("select distinct a.jobId from JobArtifact a where a.jobId in :jobIds")
    java.util.Set<UUID> findJobIdsWithArtifacts(@org.springframework.data.repository.query.Param("jobIds") java.util.Collection<UUID> jobIds);

    /** True when this stored file backs a held upload that still waits in the Inbox. */
    @org.springframework.data.jpa.repository.Query("select count(h) > 0 from HeldUpload h where h.artifactId = :artifactId and h.resolvedAt is null")
    boolean hasUnresolvedHeldUpload(@org.springframework.data.repository.query.Param("artifactId") UUID artifactId);
}
