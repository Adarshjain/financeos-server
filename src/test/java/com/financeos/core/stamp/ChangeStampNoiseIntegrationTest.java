package com.financeos.core.stamp;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.financeos.domain.job.Job;
import com.financeos.domain.job.JobJanitor;
import com.financeos.domain.job.JobRepository;
import com.financeos.domain.job.JobService;
import com.financeos.domain.job.JobStatus;
import com.financeos.domain.job.JobTrigger;
import com.financeos.domain.job.JobType;
import com.financeos.domain.job.JobWorker;
import com.financeos.domain.user.User;
import com.financeos.domain.user.UserRepository;
import com.financeos.support.UserDataCleanup;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Progress ticks do not move the change stamp; a job's status changes do (claim, finish, restart sweep). */
@SpringBootTest
class ChangeStampNoiseIntegrationTest {

    @Autowired private UserChangeStamps stamps;
    @Autowired private UserRepository userRepository;
    @Autowired private JobRepository jobRepository;
    @Autowired private JobService jobService;
    @Autowired private JobJanitor janitor;
    @Autowired private JobWorker jobWorker;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;

    private TransactionTemplate tx;
    private User user;

    @BeforeEach
    void setUp() {
        jobWorker.pause(); // a PENDING job must stay put
        tx = new TransactionTemplate(transactionManager);
        User u = new User();
        u.setEmail("stamp-noise-" + UUID.randomUUID() + "@example.test");
        u.setDisplayName("Noise Test");
        u.setPasswordHash("x");
        user = tx.execute(s -> userRepository.saveAndFlush(u));
    }

    @AfterEach
    void tearDown() {
        UserDataCleanup.deleteUsers(jdbc, List.of(user.getId()));
        jobWorker.resume();
    }

    private UUID job(JobStatus status) {
        Job job = new Job();
        job.setUserId(user.getId());
        job.setType(JobType.values()[0]);
        job.setStatus(status);
        job.setTriggerSource(JobTrigger.USER);
        return tx.execute(s -> jobRepository.saveAndFlush(job).getId());
    }

    private long version() {
        return stamps.version(user.getId());
    }

    @Test
    void aProgressTickDoesNotMoveTheStampButAStatusChangeDoes() {
        UUID id = job(JobStatus.RUNNING);
        long start = version();

        jobService.updateProgress(id, 1, 10, "reading");
        jobService.updateProgress(id, 2, 10, "reading more");
        assertEquals(start, version());

        jobService.succeed(id, "{}");
        assertEquals(start + 1, version());
    }

    @Test
    void claimingAJobMovesTheOwnersStampOnlyWhenTheClaimWins() {
        UUID id = job(JobStatus.PENDING);
        long start = version();

        assertEquals(true, jobService.claim(id));
        assertEquals(start + 1, version());

        assertEquals(false, jobService.claim(id));
        assertEquals(start + 1, version());
    }

    @Test
    void theRestartSweepStampsTheOwnersOfTheJobsItInterrupts() {
        job(JobStatus.RUNNING);
        long start = version();
        org.springframework.test.util.ReflectionTestUtils.setField(janitor, "processStart", Instant.now().plusSeconds(60));

        janitor.markInterruptedOnStartup();

        assertEquals(start + 1, version());
    }

    @Test
    void theOwnerLookupBehindTheIdKeyedBulkUpdatesFindsTheJobsUsers() {
        UUID id = job(JobStatus.FAILED);

        assertEquals(List.of(user.getId()), jobRepository.userIdsOf(List.of(id)));
        assertEquals(user.getId(), jobRepository.userIdOf(id).orElseThrow());
    }
}
