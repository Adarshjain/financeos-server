package com.financeos.domain.job;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.junit.jupiter.api.AfterEach;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Runs the real sweep query on the test database (H2), and the success path clearing failure fields. */
@SpringBootTest
@ActiveProfiles("test")
class JobStartupSweepIntegrationTest {

    @Autowired private JobRepository jobRepository;
    @Autowired private JobJanitor jobJanitor;
    @Autowired private JobService jobService;

    private final List<UUID> created = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        created.forEach(jobRepository::deleteById);
    }

    private final Instant cutoff = Instant.now().truncatedTo(ChronoUnit.SECONDS);

    private Job running(Instant startedAt) {
        Job j = new Job();
        j.setUserId(UUID.randomUUID());
        j.setType(JobType.PRICE_REFRESH);
        j.setStatus(JobStatus.RUNNING);
        j.setTriggerSource(JobTrigger.USER);
        j.setAttempt(1);
        j.setCancelRequested(false);
        j.setStartedAt(startedAt);
        Job saved = jobRepository.saveAndFlush(j);
        created.add(saved.getId());
        return saved;
    }

    private Job sweep(Job j) {
        ReflectionTestUtils.setField(jobJanitor, "processStart", cutoff);
        jobJanitor.markInterruptedOnStartup();
        return jobRepository.findById(j.getId()).orElseThrow();
    }

    @Test
    void runningJobStartedBeforeCutoff_isMarkedInterrupted() {
        Job after = sweep(running(cutoff.minusSeconds(60)));
        assertThat(after.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(after.getErrorCode()).isEqualTo("INTERRUPTED");
    }

    @Test
    void runningJobStartedAtOrAfterCutoff_isLeftRunning() {
        Job atCutoff = running(cutoff);
        Job later = running(cutoff.plusSeconds(1));
        ReflectionTestUtils.setField(jobJanitor, "processStart", cutoff);
        jobJanitor.markInterruptedOnStartup();
        assertThat(jobRepository.findById(atCutoff.getId()).orElseThrow().getStatus()).isEqualTo(JobStatus.RUNNING);
        assertThat(jobRepository.findById(later.getId()).orElseThrow().getStatus()).isEqualTo(JobStatus.RUNNING);
        assertThat(jobRepository.findById(later.getId()).orElseThrow().getErrorCode()).isNull();
    }

    @Test
    void runningJobWithNullStartedAt_isMarkedInterrupted() {
        Job after = sweep(running(null));
        assertThat(after.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(after.getErrorCode()).isEqualTo("INTERRUPTED");
    }

    @Test
    void jobThatSucceedsAfterCarryingFailureFields_endsWithThemCleared() {
        Job j = running(cutoff);
        j.setErrorCode("INTERRUPTED");
        j.setErrorMessage("Interrupted by server restart");
        j.setErrorDetail("stack");
        jobRepository.saveAndFlush(j);

        jobService.succeed(j.getId(), "{}");

        Job after = jobRepository.findById(j.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(after.getErrorCode()).isNull();
        assertThat(after.getErrorMessage()).isNull();
        assertThat(after.getErrorDetail()).isNull();
    }
}
