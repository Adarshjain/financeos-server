package com.financeos.domain.inbox.collect;

import static com.financeos.api.inbox.dto.InboxItemResponse.SECTION_NEEDS_LOOK;
import static com.financeos.api.inbox.dto.InboxItemResponse.SEVERITY_WARNING;

import com.financeos.api.inbox.dto.InboxItemResponse;
import com.financeos.core.time.AppTime;
import com.financeos.domain.inbox.InboxKinds;
import com.financeos.domain.job.Job;
import com.financeos.domain.job.JobRepository;
import com.financeos.domain.job.JobStatus;
import com.financeos.domain.job.JobTrigger;
import com.financeos.domain.job.JobType;
import com.financeos.domain.notification.job.JobNotificationService;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

/**
 * Jobs the user started that failed in the last week: a failure needs a look. One summary row counts
 * them. Successes are not shown, and cron jobs never show (the same rule as the push).
 */
@Component
public class JobInboxCollector implements InboxCollector {

    static final int RECENT_DAYS = 7;
    /** Enough to cover a busy week; older rows fall outside the window anyway. */
    static final int MAX_ROWS = 50;
    /** The Activity page, filtered to failures; it has no per-job deep link. */
    static final String HREF = "/data-sources/sync?status=FAILED";
    private static final Set<JobType> IMPORT_TYPES = Set.of(JobType.STATEMENT_INGEST, JobType.INVESTMENT_IMPORT_COMMIT,
            JobType.BROKER_RECONCILE_COMMIT);
    private static final List<JobStatus> FAILED = List.of(JobStatus.FAILED);

    private final JobRepository jobRepository;

    public JobInboxCollector(JobRepository jobRepository) {
        this.jobRepository = jobRepository;
    }

    @Override
    public List<InboxItemResponse> collect(UUID userId, LocalDate today) {
        Instant cutoff = today.minusDays(RECENT_DAYS).atStartOfDay(AppTime.zone()).toInstant();
        int failed = 0;
        boolean allImports = true;
        for (Job job : jobRepository.findByUserIdAndTypeInAndStatusInAndDismissedAtIsNull(userId, JobNotificationService.NOTIFIED_TYPES, FAILED,
                PageRequest.of(0, MAX_ROWS, Sort.by(Sort.Direction.DESC, "finishedAt")))) {
            if (job.getTriggerSource() == JobTrigger.USER && job.getFinishedAt() != null && !job.getFinishedAt().isBefore(cutoff)) {
                failed++;
                allImports &= IMPORT_TYPES.contains(job.getType());
            }
        }
        if (failed == 0) {
            return List.of();
        }
        String noun = allImports ? "import" : "task";
        return List.of(InboxRows.summary(InboxKinds.KEY_JOBS_FAILED, InboxKinds.JOB, SEVERITY_WARNING, SECTION_NEEDS_LOOK,
                failed == 1 ? "1 " + noun + " couldn't finish" : failed + " " + noun + "s couldn't finish",
                "Open the activity list to see what went wrong", HREF, failed,
                List.of(InboxRows.open(HREF), InboxRows.dismiss())));
    }
}
