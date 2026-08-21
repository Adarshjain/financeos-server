package com.financeos.domain.job;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface JobArtifactRepository extends JpaRepository<JobArtifact, UUID> {
    List<JobArtifact> findByJobId(UUID jobId);
    void deleteByJobId(UUID jobId);
}
