package com.financeos.gmail.ingest.event;

import com.financeos.domain.job.JobService;
import com.financeos.domain.user.User;
import com.financeos.gmail.domain.GmailConnection;
import com.financeos.gmail.domain.GmailConnectionRepository;
import com.financeos.gmail.domain.GmailProcessedMessage;
import com.financeos.gmail.domain.GmailProcessedStatus;
import com.financeos.gmail.domain.GmailProcessedMessageRepository;
import com.financeos.gmail.ingest.GmailIngestProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
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

    private GmailIngestEventListener listener;
    private UUID userId;

    @BeforeEach
    void setUp() {
        processedMessageRepository = mock(GmailProcessedMessageRepository.class);
        connectionRepository = mock(GmailConnectionRepository.class);
        jobService = mock(JobService.class);
        ingestProperties = mock(GmailIngestProperties.class);

        when(ingestProperties.getDateWindowDays()).thenReturn(3);

        listener = new GmailIngestEventListener(
                processedMessageRepository,
                connectionRepository,
                jobService,
                ingestProperties
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
