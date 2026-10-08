package com.financeos.domain.inbox.collect;

import static com.financeos.api.inbox.dto.InboxItemResponse.SECTION_INFO;
import static com.financeos.api.inbox.dto.InboxItemResponse.SECTION_NEEDS_LOOK;
import static com.financeos.api.inbox.dto.InboxItemResponse.SEVERITY_INFO;
import static com.financeos.api.inbox.dto.InboxItemResponse.SEVERITY_WARNING;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.api.inbox.dto.InboxItemResponse;
import com.financeos.api.inbox.dto.InboxRefsResponse;
import com.financeos.core.time.AppTime;
import com.financeos.domain.inbox.InboxKinds;
import com.financeos.domain.job.Job;
import com.financeos.domain.job.JobRepository;
import com.financeos.domain.job.JobStatus;
import com.financeos.domain.job.JobTrigger;
import com.financeos.domain.notification.job.JobMessages;
import com.financeos.domain.notification.job.JobNotificationService;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

/**
 * Jobs the user started that finished in the last week: a failure needs a look, a success is
 * information. Cron jobs never show (the same rule as the push).
 */
@Component
public class JobInboxCollector implements InboxCollector {

    static final int RECENT_DAYS = 7;
    /** Enough to cover a busy week; older rows fall outside the window anyway. */
    static final int MAX_ROWS = 50;
    private static final List<JobStatus> FINISHED = List.of(JobStatus.SUCCEEDED, JobStatus.FAILED);

    private final JobRepository jobRepository;
    private final ObjectMapper objectMapper;

    public JobInboxCollector(JobRepository jobRepository, ObjectMapper objectMapper) {
        this.jobRepository = jobRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<InboxItemResponse> collect(UUID userId, LocalDate today) {
        Instant cutoff = today.minusDays(RECENT_DAYS).atStartOfDay(AppTime.zone()).toInstant();
        List<InboxItemResponse> rows = new ArrayList<>();
        for (Job job : jobRepository.findByUserIdAndTypeInAndStatusIn(userId, JobNotificationService.NOTIFIED_TYPES, FINISHED,
                PageRequest.of(0, MAX_ROWS, Sort.by(Sort.Direction.DESC, "finishedAt")))) {
            if (job.getTriggerSource() != JobTrigger.USER || job.getFinishedAt() == null || job.getFinishedAt().isBefore(cutoff)) {
                continue;
            }
            boolean failed = job.getStatus() == JobStatus.FAILED;
            String label = JobMessages.label(job.getType());
            // The Activity page filters by type and status; it has no per-job deep link.
            String href = "/settings/activity?type=" + job.getType().name() + "&status=" + job.getStatus().name();
            rows.add(InboxRows.item(InboxKinds.jobKey(job.getId()), InboxKinds.JOB,
                    failed ? SEVERITY_WARNING : SEVERITY_INFO, failed ? SECTION_NEEDS_LOOK : SECTION_INFO,
                    failed ? label + " failed" : label + " finished",
                    failed ? JobMessages.failureReason(job) : JobMessages.summary(job, objectMapper), href, null,
                    LocalDate.ofInstant(job.getFinishedAt(), AppTime.zone()),
                    List.of(InboxRows.open(href), InboxRows.dismiss()), InboxRefsResponse.ofJob(job.getId())));
        }
        return rows;
    }
}
