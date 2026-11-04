package com.financeos.domain.job;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface JobArtifactRepository extends JpaRepository<JobArtifact, UUID> {
    List<JobArtifact> findByJobId(UUID jobId);
    void deleteByJobId(UUID jobId);

    /** What a succeeded job drops: everything except the files kept (held uploads, password retries, ingested statements). */
    void deleteByJobIdAndRetainedFalse(UUID jobId);

    /** Stored files of this user's jobs with this content hash (newest first), for "move statement". */
    @org.springframework.data.jpa.repository.Query("select a from JobArtifact a, Job j where a.jobId = j.id and j.userId = :userId "
            + "and a.retained = true and a.fileSha256 = :sha order by a.createdAt desc")
    List<JobArtifact> findByUserAndSha(@org.springframework.data.repository.query.Param("userId") UUID userId,
                                       @org.springframework.data.repository.query.Param("sha") String sha);

    /** True when this stored file backs a held upload that still waits in the Inbox. */
    @org.springframework.data.jpa.repository.Query("select count(h) > 0 from HeldUpload h where h.artifactId = :artifactId and h.resolvedAt is null")
    boolean hasUnresolvedHeldUpload(@org.springframework.data.repository.query.Param("artifactId") UUID artifactId);
}
