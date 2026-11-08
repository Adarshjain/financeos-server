package com.financeos.domain.inbox.collect;

import com.financeos.core.time.AppTime;
import com.financeos.domain.job.JobRepository;
import com.financeos.domain.job.JobStatus;
import com.financeos.domain.job.JobTrigger;
import com.financeos.domain.job.JobType;
import com.financeos.domain.notification.job.JobNotificationService;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The one definition of "a job that failed and needs a look": FAILED, not dismissed, a notified type, started by the
 * user, finished since midnight (IST) {@value #RECENT_DAYS} days ago. The Inbox jobs-failed row and the shell's data
 * sources count both read it, so they cannot drift.
 */
@Component
public class FailedJobsCounter {

    public static final int RECENT_DAYS = 7;
    static final Set<JobType> IMPORT_TYPES = Set.of(JobType.STATEMENT_INGEST, JobType.INVESTMENT_IMPORT_COMMIT,
            JobType.BROKER_RECONCILE_COMMIT);
    private static final List<JobStatus> FAILED = List.of(JobStatus.FAILED);

    private final JobRepository jobRepository;

    public FailedJobsCounter(JobRepository jobRepository) {
        this.jobRepository = jobRepository;
    }

    /** All failed jobs that need a look. */
    public long count(UUID userId, LocalDate today) {
        return count(userId, today, JobNotificationService.NOTIFIED_TYPES);
    }

    /** The same, narrowed to the import types (to word the row "import" vs "task"). */
    long countImports(UUID userId, LocalDate today) {
        return count(userId, today, IMPORT_TYPES);
    }

    private long count(UUID userId, LocalDate today, Collection<JobType> types) {
        return jobRepository.countFinishedSince(userId, types, FAILED, JobTrigger.USER,
                today.minusDays(RECENT_DAYS).atStartOfDay(AppTime.zone()).toInstant());
    }
}
