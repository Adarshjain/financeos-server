package com.financeos.api.job;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.core.exception.ResourceNotFoundException;
import com.financeos.core.exception.ValidationException;
import com.financeos.core.security.UserContext;
import com.financeos.domain.job.JobRepository;
import com.financeos.domain.job.JobService;
import com.financeos.domain.job.JobType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class JobControllerTest {

    private JobRepository jobRepository;
    private JobService jobService;
    private ObjectMapper objectMapper;
    private JobController jobController;

    @BeforeEach
    void setUp() {
        jobRepository = mock(JobRepository.class);
        jobService = mock(JobService.class);
        objectMapper = new ObjectMapper();
        jobController = new JobController(jobRepository, jobService, objectMapper);
    }

    @Test
    void getJobs_throwsValidationException_whenStatusIsInvalid() {
        UUID userId = UUID.randomUUID();
        UserContext.setCurrentUserId(userId);

        assertThatThrownBy(() -> jobController.getJobs("INVALID_STATUS", null, null))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Invalid status: INVALID_STATUS");

        UserContext.clear();
    }

    @Test
    void getJobs_throwsValidationException_whenTypeIsInvalid() {
        UUID userId = UUID.randomUUID();
        UserContext.setCurrentUserId(userId);

        assertThatThrownBy(() -> jobController.getJobs(null, "BOGUS_TYPE", null))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Invalid type: BOGUS_TYPE");

        UserContext.clear();
    }

    @Test
    void getJobs_parsesMultiTypeString() {
        UUID userId = UUID.randomUUID();
        UserContext.setCurrentUserId(userId);

        when(jobRepository.findByUserIdAndTypeIn(eq(userId), eq(List.of(JobType.PRICE_REFRESH, JobType.GMAIL_SYNC)), any()))
                .thenReturn(org.springframework.data.domain.Page.empty());

        jobController.getJobs(null, "PRICE_REFRESH, GMAIL_SYNC", null);

        verify(jobRepository).findByUserIdAndTypeIn(eq(userId), eq(List.of(JobType.PRICE_REFRESH, JobType.GMAIL_SYNC)), any());
        UserContext.clear();
    }

    @Test
    void getJob_throwsResourceNotFoundException_whenJobNotOwnedByUser() {
        UUID userIdUserA = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        UserContext.setCurrentUserId(userIdUserA);

        when(jobRepository.findByIdAndUserId(jobId, userIdUserA)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> jobController.getJobById(jobId))
                .isInstanceOf(ResourceNotFoundException.class);

        UserContext.clear();
    }

    @Test
    void cancelJob_throwsResourceNotFoundException_whenForeignJob() {
        UUID userId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        UserContext.setCurrentUserId(userId);

        when(jobService.requestCancel(userId, jobId)).thenThrow(new ResourceNotFoundException("Job", jobId));

        assertThatThrownBy(() -> jobController.cancelJob(jobId))
                .isInstanceOf(ResourceNotFoundException.class);

        UserContext.clear();
    }
}
