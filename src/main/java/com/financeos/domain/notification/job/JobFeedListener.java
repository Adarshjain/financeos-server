package com.financeos.domain.notification.job;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.core.security.UserContext;
import com.financeos.core.time.AppTime;
import com.financeos.domain.job.Job;
import com.financeos.domain.job.JobRepository;
import com.financeos.domain.job.JobStatus;
import com.financeos.domain.job.JobType;
import com.financeos.domain.notification.feed.AppNotificationKind;
import com.financeos.domain.notification.feed.AppNotificationRefs;
import com.financeos.domain.notification.feed.NotificationFeedService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Adds finished jobs to the in-app feed once the status change has committed. Independent of push: it
 * runs for every trigger and ignores push settings. Failures always notify; successes only when
 * something changed (never for Gmail sync); a failing recurring job (Gmail sync, price refresh) notifies once until it
 * succeeds again. Never throws: a feed problem must not fail a job.
 */
@Component
public class JobFeedListener {

    private static final Logger log = LoggerFactory.getLogger(JobFeedListener.class);

    private final JobRepository jobRepository;
    private final NotificationFeedService feedService;
    private final ObjectMapper objectMapper;

    public JobFeedListener(JobRepository jobRepository, NotificationFeedService feedService, ObjectMapper objectMapper) {
        this.jobRepository = jobRepository;
        this.feedService = feedService;
        this.objectMapper = objectMapper;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onJobFinished(JobFinishedEvent event) {
        UUID previous = UserContext.getCurrentUserId();
        if (previous == null && event.userId() != null) {
            UserContext.setCurrentUserId(event.userId());
        }
        try {
            handle(event);
        } catch (Exception e) {
            log.warn("Job feed entry failed for job {}: {}", event.jobId(), e.getMessage());
        } finally {
            if (previous == null) {
                UserContext.clear();
            }
        }
    }

    private void handle(JobFinishedEvent event) {
        if (event.userId() == null || (event.status() != JobStatus.SUCCEEDED && event.status() != JobStatus.FAILED)) {
            return;
        }
        Job job = jobRepository.findById(event.jobId()).orElse(null);
        if (job == null) {
            return;
        }
        JobType type = job.getType();
        boolean failed = job.getStatus() == JobStatus.FAILED;
        JsonNode result = failed ? null : readResult(job);
        String kind;
        String title;
        String body;
        switch (type) {
            case STATEMENT_INGEST -> {
                if (!failed) {
                    return;
                }
                kind = AppNotificationKind.JOB_FAILED;
                title = "Couldn't import statement";
                body = JobMessages.failureReason(job);
            }
            case GMAIL_SYNC -> {
                if (failed) {
                    if (previousAlsoFailed(job)) {
                        return;
                    }
                    kind = AppNotificationKind.JOB_FAILED;
                    title = "Gmail sync failed";
                    body = JobMessages.failureReason(job);
                } else {
                    // Successful syncs never write a feed entry.
                    return;
                }
            }
            case PRICE_REFRESH -> {
                if (!failed || previousAlsoFailed(job)) {
                    return;
                }
                kind = AppNotificationKind.JOB_FAILED;
                title = "Couldn't refresh prices";
                body = JobMessages.failureReason(job);
            }
            case INVESTMENT_IMPORT_COMMIT, BROKER_RECONCILE_COMMIT -> {
                if (failed) {
                    kind = AppNotificationKind.JOB_FAILED;
                    title = JobMessages.label(type) + " failed";
                    body = JobMessages.failureReason(job);
                } else {
                    if (count(result, "committed") <= 0) {
                        return;
                    }
                    kind = AppNotificationKind.JOB_FINISHED;
                    title = type == JobType.INVESTMENT_IMPORT_COMMIT ? "Investment import finished" : "Broker reconciliation finished";
                    body = JobMessages.summary(job, objectMapper);
                }
            }
            case RULE_APPLY -> {
                if (failed) {
                    kind = AppNotificationKind.JOB_FAILED;
                    title = "Rule apply failed";
                    body = JobMessages.failureReason(job);
                } else {
                    int applied = count(result, "appliedCount");
                    if (applied <= 0) {
                        return;
                    }
                    kind = AppNotificationKind.JOB_FINISHED;
                    String ruleName = result.path("ruleName").asText("");
                    title = ruleName.isBlank() ? "Rule applied" : "Rule '" + ruleName + "' applied";
                    body = JobMessages.plural(applied, "transaction") + " categorised";
                }
            }
            default -> {
                return;
            }
        }
        feedService.record(event.userId(), kind, title, body, AppNotificationRefs.ofJob(job.getId(), type.name()), "job:" + job.getId());
    }

    /** The user's previous finished job of this type failed too: the feed already told them. */
    private boolean previousAlsoFailed(Job job) {
        Instant finishedAt = job.getFinishedAt() != null ? job.getFinishedAt() : AppTime.now().atZone(AppTime.zone()).toInstant();
        List<Job> previous = jobRepository.findPreviousFinished(job.getUserId(), job.getType(), job.getId(),
                List.of(JobStatus.SUCCEEDED, JobStatus.FAILED), finishedAt, PageRequest.of(0, 1));
        return !previous.isEmpty() && previous.get(0).getStatus() == JobStatus.FAILED;
    }

    private JsonNode readResult(Job job) {
        if (job.getResult() == null || job.getResult().isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(job.getResult());
        } catch (Exception e) {
            return null;
        }
    }

    private static int count(JsonNode result, String field) {
        return result == null || !result.isObject() ? 0 : result.path(field).asInt(0);
    }
}
