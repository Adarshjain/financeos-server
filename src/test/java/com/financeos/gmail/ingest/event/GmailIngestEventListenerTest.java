package com.financeos.gmail.ingest.event;

import com.financeos.core.time.AppTime;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountType;
import com.financeos.domain.job.JobService;
import com.financeos.domain.user.User;
import com.financeos.gmail.domain.GmailConnection;
import com.financeos.gmail.domain.GmailConnectionRepository;
import com.financeos.gmail.domain.GmailProcessedMessage;
import com.financeos.gmail.domain.GmailProcessedStatus;
import com.financeos.gmail.domain.GmailProcessedMessageRepository;
import com.financeos.gmail.ingest.AccountResolver;
import com.financeos.gmail.ingest.GmailIngestProperties;
import com.financeos.gmail.ingest.ParkedMessageSettler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class GmailIngestEventListenerTest {

    private GmailProcessedMessageRepository processedMessageRepository;
    private GmailConnectionRepository connectionRepository;
    private JobService jobService;
    private GmailIngestProperties ingestProperties;
    private AccountResolver accountResolver;

    private GmailIngestEventListener listener;
    private UUID userId;

    @BeforeEach
    void setUp() {
        processedMessageRepository = mock(GmailProcessedMessageRepository.class);
        connectionRepository = mock(GmailConnectionRepository.class);
        jobService = mock(JobService.class);
        ingestProperties = mock(GmailIngestProperties.class);
        accountResolver = mock(AccountResolver.class);

        when(ingestProperties.getDateWindowDays()).thenReturn(3);

        listener = new GmailIngestEventListener(
                processedMessageRepository,
                connectionRepository,
                jobService,
                new ParkedMessageSettler(processedMessageRepository, accountResolver, ingestProperties)
        );

        userId = UUID.randomUUID();
    }

    @Test
    void testAccountIngestChanged_ReactivatesParkedRowsWithinWindow() {
        LocalDate ingestFromDate = LocalDate.of(2026, 8, 10);
        AccountIngestChangedEvent event = new AccountIngestChangedEvent(userId, "1234", ingestFromDate);

        GmailProcessedMessage parkedRow = new GmailProcessedMessage();
        parkedRow.setId(UUID.randomUUID());
        parkedRow.setStatus(GmailProcessedStatus.UNRESOLVED_ACCOUNT);
        parkedRow.setExtractedLast4("1234");
        parkedRow.setAttemptCount(3);
        parkedRow.setNextRetryAt(Instant.now());
        parkedRow.setError("Account unresolved");
        parkedRow.setInternalDate(LocalDate.of(2026, 8, 8).atStartOfDay(ZoneOffset.UTC).toInstant());

        when(processedMessageRepository.findParkedForReactivation(eq(userId), eq("1234"), any(), any()))
                .thenReturn(List.of(parkedRow));

        GmailConnection conn = new GmailConnection();
        conn.setId(UUID.randomUUID());
        conn.setIsConnected(true);
        User user = new User();
        user.setId(userId);
        conn.setUser(user);

        when(connectionRepository.findByUserId(userId)).thenReturn(List.of(conn));

        listener.handleAccountIngestChanged(event);

        assertThat(parkedRow.getStatus()).isEqualTo(GmailProcessedStatus.DISCOVERED);
        assertThat(parkedRow.getAttemptCount()).isEqualTo(0);
        assertThat(parkedRow.getNextRetryAt()).isNull();
        assertThat(parkedRow.getError()).isNull();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<GmailProcessedStatus>> statusCaptor = ArgumentCaptor.forClass(List.class);
        verify(processedMessageRepository).findParkedForReactivation(eq(userId), eq("1234"), statusCaptor.capture(), any());
        assertThat(statusCaptor.getValue()).containsExactlyInAnyOrder(
                GmailProcessedStatus.UNRESOLVED_ACCOUNT,
                GmailProcessedStatus.ACCOUNT_NOT_OPTED_IN,
                GmailProcessedStatus.SKIPPED_BEFORE_WATERMARK
        );

        verify(processedMessageRepository).saveAll(List.of(parkedRow));
        verify(jobService).enqueue(eq(userId), any(), any(), any(), any(), eq(conn.getId().toString()));
    }

    @Test
    void accountIngestChanged_skipsParkedEmailOlderThanTheWindow() {
        Account account = new Account("HDFC Savings", AccountType.bank_account);
        account.setId(UUID.randomUUID());
        account.setIngestFromDate(LocalDate.of(2026, 10, 7));
        when(accountResolver.resolve("0438")).thenReturn(Optional.of(new AccountResolver.ResolvedCard(account, null)));
        GmailProcessedMessage old = parked(GmailProcessedStatus.UNRESOLVED_ACCOUNT, "0438", LocalDate.of(2026, 7, 21));
        Instant windowStart = LocalDate.of(2026, 10, 4).atStartOfDay(AppTime.zone()).toInstant();
        when(processedMessageRepository.findParkedBefore(eq(userId), eq("0438"), any(), eq(windowStart))).thenReturn(List.of(old));

        listener.handleAccountIngestChanged(new AccountIngestChangedEvent(userId, "0438", account.getIngestFromDate()));

        assertThat(old.getStatus()).isEqualTo(GmailProcessedStatus.SKIPPED_BEFORE_WATERMARK);
        assertThat(old.getAccount()).isSameAs(account);
        verify(processedMessageRepository).findParkedForReactivation(eq(userId), eq("0438"), any(), eq(windowStart));
    }

    @Test
    void accountIngestChanged_withoutIngestDate_reparksUnresolvedEmailAsNotOptedIn() {
        Account account = new Account("Amazon Pay", AccountType.generic);
        account.setId(UUID.randomUUID());
        when(accountResolver.resolve("5555")).thenReturn(Optional.of(new AccountResolver.ResolvedCard(account, null)));
        GmailProcessedMessage row = parked(GmailProcessedStatus.UNRESOLVED_ACCOUNT, "5555", LocalDate.of(2026, 9, 1));
        when(processedMessageRepository.findParked(eq(userId), eq("5555"), any())).thenReturn(List.of(row));

        listener.handleAccountIngestChanged(new AccountIngestChangedEvent(userId, "5555", null));

        assertThat(row.getStatus()).isEqualTo(GmailProcessedStatus.ACCOUNT_NOT_OPTED_IN);
        assertThat(row.getAccount()).isSameAs(account);
        verify(processedMessageRepository, never()).findParkedForReactivation(any(), any(), any(), any());
    }

    @Test
    void accountIngestChanged_blankLast4_settlesNothing() {
        listener.handleAccountIngestChanged(new AccountIngestChangedEvent(userId, " ", LocalDate.of(2026, 10, 7)));

        verifyNoInteractions(accountResolver);
    }

    private GmailProcessedMessage parked(GmailProcessedStatus status, String last4, LocalDate mailDate) {
        GmailProcessedMessage row = new GmailProcessedMessage();
        row.setId(UUID.randomUUID());
        row.setStatus(status);
        row.setExtractedLast4(last4);
        row.setInternalDate(mailDate.atStartOfDay(ZoneOffset.UTC).toInstant());
        return row;
    }

    @Test
    void testAccountIngestChanged_ReactivatesWatermarkSkippedRows() {
        LocalDate ingestFromDate = LocalDate.of(2026, 8, 10);
        AccountIngestChangedEvent event = new AccountIngestChangedEvent(userId, "1234", ingestFromDate);

        GmailProcessedMessage skippedRow = new GmailProcessedMessage();
        skippedRow.setId(UUID.randomUUID());
        skippedRow.setStatus(GmailProcessedStatus.SKIPPED_BEFORE_WATERMARK);
        skippedRow.setExtractedLast4("1234");
        skippedRow.setAttemptCount(1);
        skippedRow.setNextRetryAt(Instant.now());
        skippedRow.setError("Before watermark");
        skippedRow.setInternalDate(LocalDate.of(2026, 8, 8).atStartOfDay(ZoneOffset.UTC).toInstant());

        when(processedMessageRepository.findParkedForReactivation(eq(userId), eq("1234"), any(), any()))
                .thenReturn(List.of(skippedRow));

        GmailConnection conn = new GmailConnection();
        conn.setId(UUID.randomUUID());
        conn.setIsConnected(true);
        User user = new User();
        user.setId(userId);
        conn.setUser(user);

        when(connectionRepository.findByUserId(userId)).thenReturn(List.of(conn));

        listener.handleAccountIngestChanged(event);

        assertThat(skippedRow.getStatus()).isEqualTo(GmailProcessedStatus.DISCOVERED);
        assertThat(skippedRow.getAttemptCount()).isEqualTo(0);
        assertThat(skippedRow.getNextRetryAt()).isNull();
        assertThat(skippedRow.getError()).isNull();

        verify(processedMessageRepository).saveAll(List.of(skippedRow));
    }

    @Test
    void testAccountIngestChanged_DoesNotReactivateOutsideWindow() {
        LocalDate ingestFromDate = LocalDate.of(2026, 8, 10);
        AccountIngestChangedEvent event = new AccountIngestChangedEvent(userId, "1234", ingestFromDate);

        // When repository returns empty list because internalDate is older than window (minDate cutoff)
        when(processedMessageRepository.findParkedForReactivation(eq(userId), eq("1234"), any(), any()))
                .thenReturn(List.of());

        listener.handleAccountIngestChanged(event);

        verify(processedMessageRepository, never()).saveAll(any());
    }
}
