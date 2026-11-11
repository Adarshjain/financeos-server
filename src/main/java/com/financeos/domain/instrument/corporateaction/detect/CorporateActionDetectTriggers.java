package com.financeos.domain.instrument.corporateaction.detect;

import com.financeos.core.time.AppTime;
import com.financeos.domain.job.JobService;
import com.financeos.domain.job.JobTrigger;
import com.financeos.domain.job.JobType;
import com.financeos.domain.job.handlers.CorporateActionDetectPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * When CORPORATE_ACTION_DETECT runs: nightly for every stock holder, and for one user after their holdings import.
 * Both are cron-triggered jobs (never notify the user); a per-user run is queued at most once at a time.
 */
@Component
public class CorporateActionDetectTriggers {

    private static final Logger log = LoggerFactory.getLogger(CorporateActionDetectTriggers.class);

    private final JobService jobService;
    private final CorporateActionsProperties properties;

    public CorporateActionDetectTriggers(JobService jobService, CorporateActionsProperties properties) {
        this.jobService = jobService;
        this.properties = properties;
    }

    /** Nightly run over every user holding stocks (scheduling is off on the api role). */
    @Scheduled(cron = "${corporate-actions.nightly-cron:0 40 2 * * *}", zone = "${corporate-actions.zone:Asia/Kolkata}")
    public void enqueueNightly() {
        if (!properties.isEnabled()) {
            return;
        }
        String dedupKey = "ca-detect-cron-" + AppTime.today();
        try {
            var job = jobService.enqueue(null, JobType.CORPORATE_ACTION_DETECT, JobTrigger.CRON,
                    new CorporateActionDetectPayload(null), null, dedupKey);
            log.info("Enqueued corporate action detection cron job: jobId={}, dedupKey={}", job.getId(), dedupKey);
        } catch (Exception e) {
            log.warn("Could not enqueue the nightly corporate action detection: {}", e.toString());
        }
    }

    /** After an import: check this user's holdings. Never throws, so an import never fails on it. */
    public void enqueueForUser(UUID userId) {
        if (userId == null || !properties.isEnabled()) {
            return;
        }
        try {
            jobService.enqueue(userId, JobType.CORPORATE_ACTION_DETECT, JobTrigger.CRON,
                    new CorporateActionDetectPayload(userId), null, "ca-detect-" + userId);
        } catch (Exception e) {
            log.warn("Could not enqueue corporate action detection for user {}: {}", userId, e.toString());
        }
    }
}
