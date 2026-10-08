package com.financeos.domain.job;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.domain.notification.job.JobFinishedEvent;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

/** The terminal transitions announce themselves so the "job finished" push can run after commit. */
class JobServiceEventTest {

    private final JobRepository jobRepository = mock(JobRepository.class);
    private final JobArtifactRepository artifactRepository = mock(JobArtifactRepository.class);
    private final ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
    private final JobService service = new JobService(jobRepository, artifactRepository, new ObjectMapper(), null, publisher);
    private final UUID userId = UUID.randomUUID();
    private Job job;

    @BeforeEach
    void setUp() {
        job = new Job();
        job.setId(UUID.randomUUID());
        job.setUserId(userId);
        job.setType(JobType.STATEMENT_INGEST);
        job.setStatus(JobStatus.RUNNING);
        job.setTriggerSource(JobTrigger.USER);
        when(jobRepository.findById(job.getId())).thenReturn(Optional.of(job));
        when(jobRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void succeedPublishesAFinishedEvent() {
        service.succeed(job.getId(), "{\"ok\":true}");
        ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
        verify(publisher).publishEvent(event.capture());
        assertEquals(new JobFinishedEvent(job.getId(), userId, JobType.STATEMENT_INGEST, JobStatus.SUCCEEDED, JobTrigger.USER), event.getValue());
    }

    @Test
    void failPublishesAFinishedEvent() {
        service.fail(job.getId(), "Boom", "it broke");
        ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
        verify(publisher).publishEvent(event.capture());
        assertEquals(new JobFinishedEvent(job.getId(), userId, JobType.STATEMENT_INGEST, JobStatus.FAILED, JobTrigger.USER), event.getValue());
    }

    @Test
    void cancellationAndUnknownJobsPublishNothing() {
        service.markCancelled(job.getId());
        verify(publisher, never()).publishEvent(any());

        when(jobRepository.findById(any())).thenReturn(Optional.empty());
        service.succeed(UUID.randomUUID(), "{}");
        verify(publisher, never()).publishEvent(any());
    }
}
