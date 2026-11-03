package com.financeos.gmail.accountdetect.job;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.domain.account.StatementPasswordSavedEvent;
import com.financeos.domain.job.JobRepository;
import com.financeos.domain.job.JobService;
import com.financeos.domain.job.JobTrigger;
import com.financeos.domain.job.JobType;
import com.financeos.domain.user.ProfileChangedEvent;
import com.financeos.domain.user.User;
import com.financeos.gmail.domain.GmailConnection;
import com.financeos.gmail.domain.GmailConnectionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The unlock-retry triggers: a changed name or birth date, or an account saved with a statement password. */
class AccountDetectUnlockTriggersTest {

    private final UUID userId = UUID.randomUUID();
    private final UUID connectionId = UUID.randomUUID();
    private JobService jobService;
    private GmailConnectionRepository connections;
    private UnlockTestStore store;

    @BeforeEach
    void setUp() {
        jobService = mock(JobService.class);
        connections = mock(GmailConnectionRepository.class);
        store = new UnlockTestStore();
        User user = new User();
        user.setId(userId);
        GmailConnection connection = new GmailConnection();
        connection.setId(connectionId);
        connection.setUser(user);
        connection.setIsConnected(true);
        connection.setIsPrimary(true);
        when(connections.findByUserId(userId)).thenReturn(List.of(connection));
        store.save(new AccountDetectState(userId));   // the mailbox was scanned once
    }

    private AccountDetectTriggers triggers(boolean enabled) {
        return new AccountDetectTriggers(jobService, mock(JobRepository.class), connections, store, new ObjectMapper(),
                mock(PlatformTransactionManager.class), enabled);
    }

    private void verifyRetryEnqueued(int times) {
        verify(jobService, times(times)).enqueue(eq(userId), eq(JobType.ACCOUNT_DETECT), eq(JobTrigger.CRON),
                eq(new AccountDetectPayload(connectionId, true)), isNull(), eq("account-detect:" + userId));
    }

    @Test
    void aChangedNameOrBirthDateEnqueuesAnUnlockRetryUnderTheUsersDedupKey() {
        AccountDetectTriggers t = triggers(true);
        t.onProfileChanged(new ProfileChangedEvent(userId, true, false));
        t.onProfileChanged(new ProfileChangedEvent(userId, false, true));
        verifyRetryEnqueued(2);   // the shared per-user dedup key keeps at most one queued
        assertThat(store.state(userId).orElseThrow().getUnlockGen()).isEqualTo(2);   // survives a dropped enqueue
    }

    @Test
    void onlyANameChangeAsksForTheHolderNamesToBeRecomputed() {
        AccountDetectTriggers t = triggers(true);
        t.onProfileChanged(new ProfileChangedEvent(userId, false, true));   // birth date only
        assertThat(store.state(userId).orElseThrow().getUnlockGen()).isEqualTo(1);
        assertThat(store.state(userId).orElseThrow().getHolderGen()).isZero();

        t.onProfileChanged(new ProfileChangedEvent(userId, true, false));
        assertThat(store.state(userId).orElseThrow().getUnlockGen()).isEqualTo(2);
        assertThat(store.state(userId).orElseThrow().getHolderGen()).isEqualTo(1);
    }

    @Test
    void aProfileChangeWithNothingChangedDoesNotEnqueue() {
        triggers(true).onProfileChanged(new ProfileChangedEvent(userId, false, false));
        verify(jobService, never()).enqueue(any(), any(), any(), any(), any(), any());
    }

    @Test
    void anAccountSavedWithAStatementPasswordEnqueuesAnUnlockRetry() {
        triggers(true).onStatementPasswordSaved(new StatementPasswordSavedEvent(userId));
        verifyRetryEnqueued(1);
    }

    @Test
    void theFeatureSwitchStopsBothTriggers() {
        AccountDetectTriggers t = triggers(false);
        t.onProfileChanged(new ProfileChangedEvent(userId, true, true));
        t.onStatementPasswordSaved(new StatementPasswordSavedEvent(userId));
        verify(jobService, never()).enqueue(any(), any(), any(), any(), any(), any());
    }

    @Test
    void nothingIsEnqueuedBeforeTheMailboxWasScannedOrWithoutAConnectedMailbox() {
        store.deleteForUser(userId);
        triggers(true).onProfileChanged(new ProfileChangedEvent(userId, true, true));
        verify(jobService, never()).enqueue(any(), any(), any(), any(), any(), any());

        store.save(new AccountDetectState(userId));
        when(connections.findByUserId(userId)).thenReturn(List.of());
        triggers(true).onStatementPasswordSaved(new StatementPasswordSavedEvent(userId));
        verify(jobService, never()).enqueue(any(), any(), any(), any(), any(), any());
    }

    @Test
    void anEnqueueFailureNeverReachesTheCaller() {
        when(jobService.enqueue(any(), any(), any(), any(), any(), any())).thenThrow(new IllegalStateException("queue down"));
        triggers(true).onProfileChanged(new ProfileChangedEvent(userId, true, false));   // must not throw
    }
}
