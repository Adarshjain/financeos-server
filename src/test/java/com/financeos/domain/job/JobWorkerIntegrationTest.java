package com.financeos.domain.job;

import com.financeos.core.security.UserContext;
import com.financeos.domain.job.handlers.PriceRefreshPayload;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class JobWorkerIntegrationTest {

    @Autowired
    private JobService jobService;

    @Autowired
    private JobWorker jobWorker;

    @Autowired
    private JobRepository jobRepository;

    @Test
    void poll_claimsAndExecutesJobSuccessfully_endToEnd() throws Exception {
        UUID userId = UUID.randomUUID();
        UserContext.setCurrentUserId(userId);

        Job job = jobService.enqueue(
                userId,
                JobType.PRICE_REFRESH,
                JobTrigger.USER,
                new PriceRefreshPayload(null),
                null,
                "integration-test-key"
        );
        UserContext.clear();

        assertThat(job.getStatus()).isEqualTo(JobStatus.PENDING);

        // Execute poll directly (verifying @Transactional propagation = REQUIRES_NEW claim proxy)
        jobWorker.poll();

        long deadline = System.currentTimeMillis() + 5000;
        Job updatedJob = null;
        while (System.currentTimeMillis() < deadline) {
            updatedJob = jobRepository.findById(job.getId()).orElse(null);
            if (updatedJob != null && (updatedJob.getStatus() == JobStatus.SUCCEEDED || updatedJob.getStatus() == JobStatus.FAILED)) {
                break;
            }
            Thread.sleep(100);
        }

        assertThat(updatedJob).isNotNull();
        assertThat(updatedJob.getStatus()).isEqualTo(JobStatus.SUCCEEDED);
    }
}
