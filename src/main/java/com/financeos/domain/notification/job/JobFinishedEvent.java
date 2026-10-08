package com.financeos.domain.notification.job;

import com.financeos.domain.job.JobStatus;
import com.financeos.domain.job.JobTrigger;
import com.financeos.domain.job.JobType;
import java.util.UUID;

/** Published inside the job's terminal-status transaction; consumed after it commits. */
public record JobFinishedEvent(UUID jobId, UUID userId, JobType type, JobStatus status, JobTrigger trigger) {
}
