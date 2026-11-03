package com.financeos.domain.notification.job;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.core.security.UserContext;
import com.financeos.domain.job.Job;
import com.financeos.domain.job.JobRepository;
import com.financeos.domain.job.JobStatus;
import com.financeos.domain.job.JobTrigger;
import com.financeos.domain.job.JobType;
import com.financeos.domain.notification.feed.NotificationFeedService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** ACCOUNT_DETECT runs every 2 hours: the generic job feed entry never appears for it, succeeded or failed. */
class JobFeedListenerAccountDetectTest {

    private final JobRepository jobRepository = mock(JobRepository.class);
    private final NotificationFeedService feedService = mock(NotificationFeedService.class);
    private final JobFeedListener listener = new JobFeedListener(jobRepository, feedService, new ObjectMapper());
    private final UUID userId = UUID.randomUUID();

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void accountDetectNeverWritesAFeedEntry() {
        for (JobStatus status : new JobStatus[]{JobStatus.SUCCEEDED, JobStatus.FAILED}) {
            for (JobTrigger trigger : new JobTrigger[]{JobTrigger.USER, JobTrigger.CRON}) {
                Job job = new Job();
                job.setId(UUID.randomUUID());
                job.setUserId(userId);
                job.setType(JobType.ACCOUNT_DETECT);
                job.setStatus(status);
                job.setTriggerSource(trigger);
                job.setResult("{\"status\":\"DONE\"}");
                job.setErrorMessage(status == JobStatus.FAILED ? "boom" : null);
                job.setFinishedAt(Instant.parse("2026-11-03T05:00:00Z"));
                when(jobRepository.findById(job.getId())).thenReturn(Optional.of(job));
                listener.onJobFinished(new JobFinishedEvent(job.getId(), userId, JobType.ACCOUNT_DETECT, status, trigger));
            }
        }
        verify(feedService, never()).record(any(), any(), any(), any(), any(), any());
    }
}
