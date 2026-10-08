package com.financeos.domain.notification.job;

import com.financeos.core.security.UserContext;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** Fires the "job finished" push once the status change has committed; a push problem never fails a job. */
@Component
public class JobFinishedListener {

    private static final Logger log = LoggerFactory.getLogger(JobFinishedListener.class);

    private final JobNotificationService jobNotificationService;

    public JobFinishedListener(JobNotificationService jobNotificationService) {
        this.jobNotificationService = jobNotificationService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onJobFinished(JobFinishedEvent event) {
        UUID previous = UserContext.getCurrentUserId();
        if (previous == null && event.userId() != null) {
            UserContext.setCurrentUserId(event.userId());
        }
        try {
            jobNotificationService.onJobFinished(event);
        } catch (Exception e) {
            log.warn("Job notification failed for job {}: {}", event.jobId(), e.getMessage());
        } finally {
            if (previous == null) {
                UserContext.clear();
            }
        }
    }
}
