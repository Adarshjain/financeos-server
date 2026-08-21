package com.financeos.domain.job;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Component
public class JobJanitor {

    private static final Logger log = LoggerFactory.getLogger(JobJanitor.class);

    private final JobRepository jobRepository;
    private final JobArtifactRepository artifactRepository;
    private final int retentionDays;
    private final int failedArtifactDays;

    public JobJanitor(JobRepository jobRepository,
                      JobArtifactRepository artifactRepository,
                      @Value("${jobs.retention.days:30}") int retentionDays,
                      @Value("${jobs.retention.failed-artifact-days:7}") int failedArtifactDays) {
        this.jobRepository = jobRepository;
        this.artifactRepository = artifactRepository;
        this.retentionDays = retentionDays;
        this.failedArtifactDays = failedArtifactDays;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void markInterruptedOnStartup() {
        int count = jobRepository.markInterrupted(
                JobStatus.RUNNING,
                JobStatus.FAILED,
                "INTERRUPTED",
                "Interrupted by server restart",
                Instant.now()
        );
        if (count > 0) {
            log.info("Startup sweep marked {} stale RUNNING job(s) as FAILED/INTERRUPTED", count);
        }
    }

    @Scheduled(cron = "${jobs.retention.cron:0 30 3 * * *}", zone = "${jobs.retention.zone:Asia/Kolkata}")
    @Transactional
    public void runRetentionPurge() {
        Instant failedArtifactCutoff = Instant.now().minus(Duration.ofDays(failedArtifactDays));
        int deletedArtifacts = jobRepository.deleteFailedJobArtifactsOlderThan(failedArtifactCutoff);
        if (deletedArtifacts > 0) {
            log.info("JobJanitor retention purge deleted {} artifact(s) for FAILED jobs older than {} days", deletedArtifacts, failedArtifactDays);
        }

        Instant cutoff = Instant.now().minus(Duration.ofDays(retentionDays));
        int deletedJobs = jobRepository.deleteTerminalJobsOlderThan(
                List.of(JobStatus.SUCCEEDED, JobStatus.FAILED, JobStatus.CANCELLED),
                cutoff
        );

        if (deletedJobs > 0) {
            log.info("JobJanitor retention purge deleted {} job(s) older than {} days", deletedJobs, retentionDays);
        }
    }
}
