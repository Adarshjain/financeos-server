package com.financeos.gmail.ingest;

import com.financeos.core.time.AppTime;
import com.financeos.domain.categorization.CategorizationService;
import com.financeos.domain.transaction.TransactionRepository;
import com.financeos.domain.user.User;
import com.financeos.gmail.domain.*;
import com.financeos.gmail.engine.GmailEngine;
import com.financeos.gmail.ingest.gemini.GeminiExtractor;
import com.financeos.gmail.reconcile.StatementReconciliationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** A sender added for an account backfills no further back than that account's date, whatever the user's demand floor. */
class GmailIngestionBackfillFloorTest {

    private GmailEngine gmailEngine;
    private GmailSyncCursorService cursorService;
    private GmailBackfillDemandRepository demandRepository;
    private GmailSenderRepository senderRepository;
    private GmailIngestionService service;
    private GmailConnection connection;
    private User user;

    @BeforeEach
    void setUp() {
        gmailEngine = mock(GmailEngine.class);
        cursorService = mock(GmailSyncCursorService.class);
        demandRepository = mock(GmailBackfillDemandRepository.class);
        senderRepository = mock(GmailSenderRepository.class);
        GmailIngestProperties props = mock(GmailIngestProperties.class);
        PlatformTransactionManager tm = mock(PlatformTransactionManager.class);
        when(tm.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        service = new GmailIngestionService(gmailEngine, cursorService, senderRepository,
                mock(AccountResolver.class), mock(GmailTransactionWriter.class), mock(GeminiExtractor.class),
                mock(GmailProcessedMessageRepository.class), demandRepository, props,
                mock(StatementReconciliationService.class), mock(CategorizationService.class),
                mock(TransactionRepository.class), tm, mock(ParkedMessageSettler.class));
        when(props.getProcessBudget()).thenReturn(100);
        when(props.getBackfillProcessBudget()).thenReturn(500);
        when(props.getOverlapLapMinutes()).thenReturn(15);
        when(props.getStaleProcessingMinutes()).thenReturn(60);
        when(props.getRetryMaxAttempts()).thenReturn(5);
        when(gmailEngine.listMessageIds(any(), anyString())).thenReturn(List.of());

        UUID userId = UUID.randomUUID();
        user = new User();
        user.setId(userId);
        connection = new GmailConnection();
        connection.setId(UUID.randomUUID());
        connection.setUser(user);
        connection.setEmail("user@example.com");
        when(demandRepository.findById(userId)).thenReturn(Optional.of(new GmailBackfillDemand(user, LocalDate.of(2024, 1, 1))));
    }

    private GmailSyncCursor cursor(String address, LocalDate backfillFrom, Instant covered) {
        GmailSender sender = new GmailSender();
        sender.setId(UUID.randomUUID());
        sender.setUser(user);
        sender.setSenderAddress(address);
        sender.setEnabled(true);
        sender.setBackfillFrom(backfillFrom);
        GmailSyncCursor c = new GmailSyncCursor();
        c.setUser(user);
        c.setConnection(connection);
        c.setSender(sender);
        c.setLastListedAt(Instant.now());
        c.setEarliestCoveredAt(covered);
        return c;
    }

    private void withCursors(GmailSyncCursor... cursors) {
        when(cursorService.getOrSeedCursors(connection)).thenReturn(List.of(cursors));
        when(senderRepository.findByUserIdAndEnabledTrue(user.getId()))
                .thenReturn(Arrays.stream(cursors).map(GmailSyncCursor::getSender).toList());
    }

    private static long epoch(LocalDate d) {
        return d.atStartOfDay(AppTime.zone()).toEpochSecond();
    }

    private List<String> backfillQueries() {
        ArgumentCaptor<String> q = ArgumentCaptor.forClass(String.class);
        verify(gmailEngine, atLeast(0)).listMessageIds(eq(connection), q.capture());
        return q.getAllValues().stream().filter(s -> s.contains(" before:")).toList();
    }

    @Test
    void flooredSender_backfillsOnlyToItsFloor_whileAnUnflooredOneFollowsTheDemandFloor() {
        Instant covered = Instant.now().minus(Duration.ofDays(90));
        GmailSyncCursor floored = cursor("hdfcbank.net", LocalDate.of(2026, 2, 1), covered);
        GmailSyncCursor plain = cursor("alerts@other.com", null, covered);
        withCursors(floored, plain);

        service.syncConnection(connection);

        List<String> queries = backfillQueries();
        assertThat(queries).hasSize(2);
        assertThat(queries).anyMatch(q -> q.contains("hdfcbank.net") && q.contains("after:" + epoch(LocalDate.of(2026, 2, 1))));
        assertThat(queries).anyMatch(q -> q.contains("alerts@other.com") && q.contains("after:" + epoch(LocalDate.of(2024, 1, 1))));
        assertThat(queries).noneMatch(q -> q.contains("hdfcbank.net") && q.contains("alerts@other.com"));
        verify(cursorService).updateEarliestCoveredAt(eq(List.of(floored)),
                eq(LocalDate.of(2026, 2, 1).atStartOfDay(AppTime.zone()).toInstant()));
    }

    @Test
    void floorOlderThanTheDemandFloor_doesNotReachFurtherThanTheDemandFloor() {
        Instant covered = Instant.now().minus(Duration.ofDays(90));
        GmailSyncCursor c = cursor("hdfcbank.net", LocalDate.of(2020, 1, 1), covered);
        withCursors(c);

        service.syncConnection(connection);

        assertThat(backfillQueries()).singleElement().satisfies(q -> assertThat(q).contains("after:" + epoch(LocalDate.of(2024, 1, 1))));
    }

    @Test
    void senderAlreadyCoveredToItsFloor_isNotBackfilledAgain() {
        GmailSyncCursor c = cursor("hdfcbank.net", LocalDate.of(2026, 2, 1),
                LocalDate.of(2026, 2, 1).atStartOfDay(AppTime.zone()).toInstant());
        withCursors(c);

        service.syncConnection(connection);

        assertThat(backfillQueries()).isEmpty();
        verify(cursorService, never()).updateEarliestCoveredAt(any(), any());
    }
}
