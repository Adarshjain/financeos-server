package com.financeos.domain.job;

import com.financeos.core.security.UserContext;
import com.financeos.domain.job.handlers.PriceRefreshPayload;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the real JPQL against the test database: pendingStats() is a plain SELECT and claim() an
 * UPDATE that needs @Modifying. A misplaced annotation breaks one or the other at runtime only.
 */
@SpringBootTest
@ActiveProfiles("test")
class JobRepositoryPendingStatsIntegrationTest {

    @Autowired
    private JobService jobService;

    @Autowired
    private JobRepository jobRepository;

    @Test
    void pendingStats_countsPendingJobs_andClaimRemovesThem() {
        UUID userId = UUID.randomUUID();
        UserContext.setCurrentUserId(userId);
        Job job;
        try {
            job = jobService.enqueue(userId, JobType.PRICE_REFRESH, JobTrigger.USER,
                    new PriceRefreshPayload(null), null, "pending-stats-" + UUID.randomUUID());
        } finally {
            UserContext.clear();
        }

        List<Object[]> before = jobRepository.pendingStats();
        assertThat(before).hasSize(1);
        assertThat(((Number) before.get(0)[0]).longValue()).isGreaterThanOrEqualTo(1);
        assertThat(before.get(0)[1]).isNotNull();

        assertThat(jobService.claim(job.getId())).isTrue();   // the production path: REQUIRES_NEW + @Modifying UPDATE
        assertThat(jobRepository.findById(job.getId()).orElseThrow().getStatus()).isEqualTo(JobStatus.RUNNING);

        long after = ((Number) jobRepository.pendingStats().get(0)[0]).longValue();
        assertThat(after).isEqualTo(((Number) before.get(0)[0]).longValue() - 1);
    }
}
