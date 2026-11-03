package com.financeos.gmail.accountdetect.job;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.domain.job.Job;
import com.financeos.domain.job.JobRepository;
import com.financeos.domain.job.JobService;
import com.financeos.domain.job.JobStatus;
import com.financeos.domain.job.JobTrigger;
import com.financeos.domain.job.JobType;
import com.financeos.domain.job.JobExecutionContext;
import com.financeos.domain.notification.job.JobFinishedEvent;
import com.financeos.domain.user.HelloCompletedEvent;
import com.financeos.domain.user.ProfileService;
import com.financeos.domain.user.User;
import com.financeos.domain.user.UserRepository;
import com.financeos.gmail.domain.GmailConnection;
import com.financeos.gmail.domain.GmailConnectionRepository;
import com.financeos.gmail.ingest.GmailSenderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** ACCOUNT_DETECT starts only after the user finished the Hello screen. */
class AccountDetectHelloGateTest {

    private final UUID userId = UUID.randomUUID();
    private final UUID connectionId = UUID.randomUUID();
    private final UUID syncJobId = UUID.randomUUID();
    private JobService jobService;
    private GmailConnectionRepository connections;
    private UserRepository users;
    private User user;
    private GmailConnection connection;

    @BeforeEach
    void setUp() {
        jobService = mock(JobService.class);
        JobRepository jobRepository = mock(JobRepository.class);
        connections = mock(GmailConnectionRepository.class);
        users = mock(UserRepository.class);
        user = new User();
        user.setId(userId);
        when(users.findById(userId)).thenReturn(Optional.of(user));
        connection = new GmailConnection();
        connection.setId(connectionId);
        connection.setUser(user);
        connection.setIsConnected(true);
        connection.setIsPrimary(true);
        when(connections.findById(connectionId)).thenReturn(Optional.of(connection));
        when(connections.findByUserId(userId)).thenReturn(List.of(connection));
        Job sync = new Job();
        sync.setId(syncJobId);
        sync.setUserId(userId);
        sync.setType(JobType.GMAIL_SYNC);
        sync.setPayload("{\"connectionId\":\"" + connectionId + "\"}");
        when(jobRepository.findById(syncJobId)).thenReturn(Optional.of(sync));
        triggers = new AccountDetectTriggers(jobService, jobRepository, connections, users, new InMemoryAccountDetectStore(),
                new ObjectMapper(), mock(PlatformTransactionManager.class), true);
    }

    private AccountDetectTriggers triggers;

    private void verifyEnqueued(int times) {
        verify(jobService, times(times)).enqueue(eq(userId), eq(JobType.ACCOUNT_DETECT), eq(JobTrigger.CRON),
                eq(new AccountDetectPayload(connectionId)), isNull(), eq("account-detect:" + userId));
    }

    @Test
    void gmailConnectedBeforeHelloEnqueuesNothing() {
        triggers.onConnected(userId, connection);
        verify(jobService, never()).enqueue(any(), any(), any(), any(), any(), anyString());
    }

    @Test
    void gmailSyncFinishedBeforeHelloEnqueuesNothing() {
        triggers.onJobFinished(new JobFinishedEvent(syncJobId, userId, JobType.GMAIL_SYNC, JobStatus.SUCCEEDED, JobTrigger.CRON));
        verify(jobService, never()).enqueue(any(), any(), any(), any(), any(), anyString());
    }

    @Test
    void helloCompletedWithGmailConnectedEnqueuesOneScan() {
        user.setHelloDoneAt(Instant.now());
        triggers.onHelloCompleted(new HelloCompletedEvent(userId));
        verifyEnqueued(1);
    }

    @Test
    void helloCompletedWithoutGmailEnqueuesNothing() {
        user.setHelloDoneAt(Instant.now());
        when(connections.findByUserId(userId)).thenReturn(List.of());
        triggers.onHelloCompleted(new HelloCompletedEvent(userId));
        verify(jobService, never()).enqueue(any(), any(), any(), any(), any(), anyString());
    }

    @Test
    void helloCompletedWithGmailDisconnectedEnqueuesNothing() {
        user.setHelloDoneAt(Instant.now());
        connection.setIsConnected(false);
        triggers.onHelloCompleted(new HelloCompletedEvent(userId));
        verify(jobService, never()).enqueue(any(), any(), any(), any(), any(), anyString());
    }

    @Test
    void secondHelloCallDoesNotPublishTheEventAgain() {
        ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
        ProfileService profile = new ProfileService(users, events);
        profile.hello(userId, "Asha", null, true);
        profile.hello(userId, "Asha", null, true);
        verify(events, times(1)).publishEvent(new HelloCompletedEvent(userId));
        assertThat(user.getHelloDoneAt()).isNotNull();
    }

    @Test
    void handlerSkipsWhenHelloIsNotDone() throws Exception {
        AccountDetectJobHandler handler = new AccountDetectJobHandler(connections, mock(GmailSenderRepository.class),
                new InMemoryAccountDetectStore(), c -> { throw new AssertionError("mailbox must not be opened"); }, new FirstScanGuard(),
                triggers, (u, d, c) -> { throw new AssertionError("no detection"); }, mock(JdbcTemplate.class), 12, 50, 120);
        handler.users = users;
        JobExecutionContext ctx = mock(JobExecutionContext.class);
        when(ctx.getUserId()).thenReturn(userId);
        when(ctx.payload(AccountDetectPayload.class)).thenReturn(new AccountDetectPayload(connectionId));
        Object result = handler.execute(ctx);
        assertThat(((Map<?, ?>) result).get("status")).isEqualTo("SKIPPED_ONBOARDING");
    }
}
