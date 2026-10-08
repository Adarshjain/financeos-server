package com.financeos.domain.notification.job;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.core.time.AppTime;
import com.financeos.domain.job.Job;
import com.financeos.domain.job.JobRepository;
import com.financeos.domain.job.JobStatus;
import com.financeos.domain.job.JobTrigger;
import com.financeos.domain.job.JobType;
import com.financeos.domain.notification.NotificationKind;
import com.financeos.domain.notification.NotificationPrefs;
import com.financeos.domain.notification.NotificationPrefsLoader;
import com.financeos.domain.notification.NotificationSettingsService;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * "Your import finished / failed" for jobs the user started themselves (never cron jobs: a
 * 2-hourly Gmail sync would be noise). Event-driven only; the marker is {@code jobs.notified_at}
 * and is set even when nothing can be sent.
 */
@Service
public class JobNotificationService {

    private static final Logger log = LoggerFactory.getLogger(JobNotificationService.class);

    /** The long, user-started operations worth a push when the tab is gone. */
    static final Set<JobType> NOTIFIED_TYPES = Set.of(
            JobType.STATEMENT_INGEST, JobType.INVESTMENT_IMPORT_COMMIT, JobType.BROKER_RECONCILE_COMMIT, JobType.RULE_APPLY);

    private final JobRepository jobRepository;
    private final NotificationPrefsLoader prefsLoader;
    private final NotificationSettingsService settingsService;
    private final ObjectMapper objectMapper;

    public JobNotificationService(JobRepository jobRepository,
                                  NotificationPrefsLoader prefsLoader,
                                  NotificationSettingsService settingsService,
                                  ObjectMapper objectMapper) {
        this.jobRepository = jobRepository;
        this.prefsLoader = prefsLoader;
        this.settingsService = settingsService;
        this.objectMapper = objectMapper;
    }

    /** Whether this terminal transition is one the user is told about at all. */
    static boolean notifiable(JobFinishedEvent event) {
        return event.userId() != null
                && event.trigger() == JobTrigger.USER
                && NOTIFIED_TYPES.contains(event.type())
                && (event.status() == JobStatus.SUCCEEDED || event.status() == JobStatus.FAILED);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int onJobFinished(JobFinishedEvent event) {
        if (!notifiable(event)) {
            return 0;
        }
        Job job = jobRepository.findById(event.jobId()).orElse(null);
        if (job == null || job.getNotifiedAt() != null) {
            return 0;
        }
        NotificationPrefs prefs = prefsLoader.load(event.userId());
        int sent = 0;
        if (prefs.deliverable(NotificationKind.JOB_FINISHED)) {
            sent = settingsService.deliver(prefs.settings(), JobMessages.forJob(job, objectMapper));
            log.info("Job notification: jobId={}, type={}, status={}, devices={}", job.getId(), job.getType(), job.getStatus(), sent);
        }
        job.setNotifiedAt(AppTime.now().atZone(AppTime.zone()).toInstant());
        jobRepository.save(job);
        return sent;
    }
}
