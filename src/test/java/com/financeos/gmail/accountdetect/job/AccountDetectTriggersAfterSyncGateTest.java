package com.financeos.gmail.accountdetect.job;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.domain.job.Job;
import com.financeos.domain.job.JobRepository;
import com.financeos.domain.job.JobService;
import com.financeos.domain.job.JobStatus;
import com.financeos.domain.job.JobTrigger;
import com.financeos.domain.job.JobType;
import com.financeos.domain.notification.job.JobFinishedEvent;
import com.financeos.domain.user.User;
import com.financeos.gmail.domain.GmailConnection;
import com.financeos.gmail.domain.GmailConnectionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AccountDetectTriggersAfterSyncGateTest {

    private final UUID userId = UUID.randomUUID();
    private final UUID connectionId = UUID.randomUUID();
    private final UUID syncJobId = UUID.randomUUID();
    private JobService jobService;
    private InMemoryAccountDetectStore store;
    private Job sync;
    private AccountDetectTriggers triggers;

    @BeforeEach
    void setUp() {
        jobService = mock(JobService.class);
        JobRepository jobRepository = mock(JobRepository.class);
        GmailConnectionRepository connections = mock(GmailConnectionRepository.class);
        store = new InMemoryAccountDetectStore();
        User user = new User();
        user.setId(userId);
        user.setHelloDoneAt(Instant.now());
        com.financeos.domain.user.UserRepository users = mock(com.financeos.domain.user.UserRepository.class);
        when(users.findById(userId)).thenReturn(Optional.of(user));
        GmailConnection connection = new GmailConnection();
        connection.setId(connectionId);
        connection.setUser(user);
        connection.setIsConnected(true);
        connection.setIsPrimary(true);
        when(connections.findById(connectionId)).thenReturn(Optional.of(connection));
        when(connections.findByUserId(userId)).thenReturn(List.of(connection));
        sync = new Job();
        sync.setId(syncJobId);
        sync.setUserId(userId);
        sync.setType(JobType.GMAIL_SYNC);
        sync.setPayload("{\"connectionId\":\"" + connectionId + "\"}");
        sync.setResult("{\"discovered\":0,\"created\":0}");
        when(jobRepository.findById(syncJobId)).thenReturn(Optional.of(sync));
        triggers = new AccountDetectTriggers(jobService, jobRepository, connections, users, store, new ObjectMapper(),
                mock(PlatformTransactionManager.class), true);
    }

    private void lastRun(Integer minutesAgo) {
        AccountDetectState s = new AccountDetectState();
        s.setUserId(userId);
        s.setConnectionId(connectionId);
        s.setLastRunAt(minutesAgo == null ? null : Instant.now().minus(Duration.ofMinutes(minutesAgo)));
        store.save(s);
    }

    private void finish(JobType type, JobStatus status, JobTrigger trigger) {
        triggers.onJobFinished(new JobFinishedEvent(syncJobId, userId, type, status, trigger));
    }

    private void verifyEnqueued(int times) {
        verify(jobService, times(times)).enqueue(eq(userId), eq(JobType.ACCOUNT_DETECT), eq(JobTrigger.CRON),
                eq(new AccountDetectPayload(connectionId)), isNull(), eq("account-detect:" + userId));
    }

    @Test
    void userTriggerAlwaysEnqueues() {
        lastRun(10);
        finish(JobType.GMAIL_SYNC, JobStatus.SUCCEEDED, JobTrigger.USER);
        verifyEnqueued(1);
    }

    @Test
    void discoveredMailEnqueues() {
        lastRun(10);
        sync.setResult("{\"discovered\":3,\"created\":0}");
        finish(JobType.GMAIL_SYNC, JobStatus.SUCCEEDED, JobTrigger.CRON);
        verifyEnqueued(1);
    }

    @Test
    void createdTransactionsEnqueue() {
        lastRun(10);
        sync.setResult("{\"discovered\":0,\"created\":2}");
        finish(JobType.GMAIL_SYNC, JobStatus.SUCCEEDED, JobTrigger.CRON);
        verifyEnqueued(1);
    }

    @Test
    void noNewMailAndRecentRunSkips() {
        lastRun(10);
        finish(JobType.GMAIL_SYNC, JobStatus.SUCCEEDED, JobTrigger.CRON);
        verifyEnqueued(0);
    }

    @Test
    void noNewMailButQuietTooLongEnqueues() {
        lastRun(120);
        finish(JobType.GMAIL_SYNC, JobStatus.SUCCEEDED, JobTrigger.CRON);
        verifyEnqueued(1);
    }

    @Test
    void noStateEnqueues() {
        finish(JobType.GMAIL_SYNC, JobStatus.SUCCEEDED, JobTrigger.CRON);
        verifyEnqueued(1);
    }

    @Test
    void nullLastRunEnqueues() {
        lastRun(null);
        finish(JobType.GMAIL_SYNC, JobStatus.SUCCEEDED, JobTrigger.CRON);
        verifyEnqueued(1);
    }

    @Test
    void blankOrUnparseableResultEnqueues() {
        lastRun(10);
        sync.setResult(" ");
        finish(JobType.GMAIL_SYNC, JobStatus.SUCCEEDED, JobTrigger.CRON);
        sync.setResult("not json {");
        finish(JobType.GMAIL_SYNC, JobStatus.SUCCEEDED, JobTrigger.CRON);
        sync.setResult(null);
        finish(JobType.GMAIL_SYNC, JobStatus.SUCCEEDED, JobTrigger.CRON);
        verifyEnqueued(3);
    }

    @Test
    void otherJobTypeOrFailedSyncIsIgnored() {
        finish(JobType.ACCOUNT_DETECT, JobStatus.SUCCEEDED, JobTrigger.USER);
        finish(JobType.GMAIL_SYNC, JobStatus.FAILED, JobTrigger.USER);
        verify(jobService, times(0)).enqueue(any(), any(), any(), any(), any(), anyString());
    }
}
