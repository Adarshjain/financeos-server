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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
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

class AccountDetectTriggersTest {

    private final UUID userId = UUID.randomUUID();
    private final UUID connectionId = UUID.randomUUID();
    private final UUID syncJobId = UUID.randomUUID();
    private JobService jobService;
    private JobRepository jobRepository;
    private GmailConnectionRepository connections;
    private InMemoryAccountDetectStore store;
    private GmailConnection connection;

    @BeforeEach
    void setUp() {
        jobService = mock(JobService.class);
        jobRepository = mock(JobRepository.class);
        connections = mock(GmailConnectionRepository.class);
        store = new InMemoryAccountDetectStore();
        User user = new User();
        user.setId(userId);
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
    }

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) TransactionSynchronizationManager.clearSynchronization();
    }

    private AccountDetectTriggers triggers(boolean enabled) {
        return new AccountDetectTriggers(jobService, jobRepository, connections, store, new ObjectMapper(),
                mock(PlatformTransactionManager.class), enabled);
    }

    private JobFinishedEvent syncFinished(JobStatus status) {
        return new JobFinishedEvent(syncJobId, userId, JobType.GMAIL_SYNC, status, JobTrigger.CRON);
    }

    private void verifyEnqueued(int times) {
        verify(jobService, times(times)).enqueue(eq(userId), eq(JobType.ACCOUNT_DETECT), eq(JobTrigger.CRON),
                eq(new AccountDetectPayload(connectionId)), isNull(), eq("account-detect:" + userId));
    }

    @Test
    void aSuccessfulGmailSyncEnqueuesOneScanPerUserByDedupKey() {
        AccountDetectTriggers t = triggers(true);
        t.onJobFinished(syncFinished(JobStatus.SUCCEEDED));
        t.onJobFinished(syncFinished(JobStatus.SUCCEEDED));
        // both carry the same per-user dedup key, so JobService keeps at most one pending or running
        verifyEnqueued(2);
    }

    @Test
    void failedSyncsOtherJobsAndTheSwitchDoNotEnqueue() {
        triggers(true).onJobFinished(syncFinished(JobStatus.FAILED));
        triggers(true).onJobFinished(new JobFinishedEvent(syncJobId, userId, JobType.RULE_APPLY, JobStatus.SUCCEEDED, JobTrigger.USER));
        triggers(false).onJobFinished(syncFinished(JobStatus.SUCCEEDED));
        triggers(false).onConnected(userId, connection);
        verify(jobService, never()).enqueue(any(), any(), any(), any(), any(), anyString());
    }

    @Test
    void onlyTheScannedMailboxEnqueuesWhileThePrimaryIsConnected() {
        connection.setIsPrimary(false);
        GmailConnection primary = new GmailConnection();
        primary.setId(UUID.randomUUID());
        primary.setUser(connection.getUser());
        primary.setIsConnected(true);
        primary.setIsPrimary(true);
        when(connections.findByUserId(userId)).thenReturn(List.of(primary, connection));
        triggers(true).onJobFinished(syncFinished(JobStatus.SUCCEEDED));
        triggers(true).onConnected(userId, connection);
        connection.setIsPrimary(true);
        primary.setIsPrimary(false);
        connection.setIsConnected(false);
        triggers(true).onJobFinished(syncFinished(JobStatus.SUCCEEDED));
        verify(jobService, never()).enqueue(any(), any(), any(), any(), any(), anyString());
    }

    @Test
    void withThePrimaryDisconnectedTheOldestOtherConnectedMailboxIsScanned() {
        connection.setIsPrimary(false);
        GmailConnection primary = new GmailConnection();
        primary.setId(UUID.randomUUID());
        primary.setUser(connection.getUser());
        primary.setIsConnected(false);
        primary.setIsPrimary(true);
        GmailConnection newer = new GmailConnection();
        newer.setId(UUID.randomUUID());
        newer.setIsConnected(true);
        connection.setCreatedAt(java.time.Instant.parse("2025-01-01T00:00:00Z"));
        newer.setCreatedAt(java.time.Instant.parse("2025-06-01T00:00:00Z"));
        when(connections.findByUserId(userId)).thenReturn(List.of(primary, newer, connection));

        assertThat(AccountDetectTriggers.scanTarget(List.of(primary, newer, connection))).isSameAs(connection);
        triggers(true).onJobFinished(syncFinished(JobStatus.SUCCEEDED));
        verifyEnqueued(1);

        primary.setIsConnected(true);
        assertThat(AccountDetectTriggers.scanTarget(List.of(primary, newer, connection))).isSameAs(primary);
        assertThat(AccountDetectTriggers.scanTarget(List.of(newer))).isSameAs(newer);
        newer.setIsConnected(false);
        connection.setIsConnected(false);
        primary.setIsConnected(false);
        assertThat(AccountDetectTriggers.scanTarget(List.of(primary, newer, connection))).isNull();
    }

    @Test
    void connectingEnqueuesOnlyAfterTheConnectCommits() {
        TransactionSynchronizationManager.initSynchronization();
        triggers(true).onConnected(userId, connection);
        verify(jobService, never()).enqueue(any(), any(), any(), any(), any(), anyString());

        for (TransactionSynchronization s : TransactionSynchronizationManager.getSynchronizations()) s.afterCommit();
        verifyEnqueued(1);
    }

    @Test
    void connectingOutsideATransactionEnqueuesAtOnce() {
        triggers(true).onConnected(userId, connection);
        verifyEnqueued(1);
    }

    @Test
    void anEnqueueFailureNeverReachesTheCaller() {
        when(jobService.enqueue(any(), any(), any(), any(), any(), anyString())).thenThrow(new IllegalStateException("db down"));
        triggers(true).onJobFinished(syncFinished(JobStatus.SUCCEEDED));
        triggers(true).onConnected(userId, connection);
    }

    @Test
    void disconnectingTheScannedMailboxDeletesStateAndFacts() {
        AccountDetectState state = new AccountDetectState(userId);
        state.setConnectionId(connectionId);
        store.save(state);
        store.rows.computeIfAbsent(userId, k -> new java.util.ArrayList<>()).add(new AccountDetectFact());
        UUID otherUser = UUID.randomUUID();
        store.save(new AccountDetectState(otherUser));

        triggers(true).onDisconnected(userId, UUID.randomUUID());   // another mailbox of the user: kept
        assertThat(store.state(userId)).isPresent();

        triggers(true).onDisconnected(userId, connectionId);
        assertThat(store.state(userId)).isEmpty();
        assertThat(store.rows(userId)).isEmpty();
        assertThat(store.state(otherUser)).isPresent();
    }
}
