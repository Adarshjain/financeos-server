package com.financeos.domain.inbox.collect;

import static com.financeos.api.inbox.dto.InboxItemResponse.SECTION_NEEDS_LOOK;
import static com.financeos.api.inbox.dto.InboxItemResponse.SEVERITY_WARNING;

import com.financeos.api.inbox.dto.InboxItemResponse;
import com.financeos.domain.inbox.InboxKinds;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Jobs the user started that failed in the last week: a failure needs a look. One summary row counts
 * them. Successes are not shown, and cron jobs never show (the same rule as the push).
 */
@Component
public class JobInboxCollector implements InboxCollector {

    /** The Activity page, filtered to failures; it has no per-job deep link. */
    static final String HREF = "/data-sources/sync?status=FAILED";
    private final FailedJobsCounter failedJobs;

    public JobInboxCollector(FailedJobsCounter failedJobs) {
        this.failedJobs = failedJobs;
    }

    @Override
    public List<InboxItemResponse> collect(UUID userId, LocalDate today) {
        int failed = (int) Math.min(failedJobs.count(userId, today), Integer.MAX_VALUE);
        if (failed == 0) {
            return List.of();
        }
        boolean allImports = failedJobs.countImports(userId, today) == failed;
        String noun = allImports ? "import" : "task";
        return List.of(InboxRows.summary(InboxKinds.KEY_JOBS_FAILED, InboxKinds.JOB, SEVERITY_WARNING, SECTION_NEEDS_LOOK,
                failed == 1 ? "1 " + noun + " couldn't finish" : failed + " " + noun + "s couldn't finish",
                "Open the activity list to see what went wrong", HREF, failed,
                List.of(InboxRows.open(HREF), InboxRows.dismiss())));
    }
}
