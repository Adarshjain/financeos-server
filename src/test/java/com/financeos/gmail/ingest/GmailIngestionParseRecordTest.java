package com.financeos.gmail.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import com.financeos.domain.statement.StatementParseRecords;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.financeos.core.observability.ObservabilityMetrics;
import com.financeos.domain.categorization.CategorizationService;
import com.financeos.domain.statement.file.StatementFileAttacher;
import com.financeos.domain.transaction.TransactionRepository;
import com.financeos.domain.user.User;
import com.financeos.gmail.classify.EmailClassifier;
import com.financeos.gmail.domain.GmailBackfillDemandRepository;
import com.financeos.gmail.domain.GmailConnection;
import com.financeos.gmail.domain.GmailProcessedMessage;
import com.financeos.gmail.domain.GmailProcessedMessageRepository;
import com.financeos.gmail.domain.GmailProcessedStatus;
import com.financeos.gmail.domain.GmailSyncCursor;
import com.financeos.gmail.engine.GmailEngine;
import com.financeos.gmail.ingest.gemini.GeminiExtractor;
import com.financeos.gmail.internal.GmailAttachment;
import com.financeos.gmail.internal.GmailMessage;
import com.financeos.gmail.reconcile.ReconSummary;
import com.financeos.gmail.reconcile.StatementFileHandoff;
import com.financeos.gmail.reconcile.StatementReconciliationService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

/**
 * The original file of a Gmail statement is kept by the caller after the reconcile transaction has committed:
 * never inside it, and never when it rolled back.
 */
class GmailIngestionParseRecordTest {

    private final byte[] bytes = {9, 8, 7};
    private final UUID statementId = UUID.randomUUID();
    private final UUID accountId = UUID.randomUUID();
    private GmailEngine gmailEngine;
    private GmailProcessedMessageRepository processedMessageRepository;
    private StatementReconciliationService reconciliation;
    private StatementFileAttacher attacher;
    private GmailIngestionService service;
    private GmailConnection connection;
    private User user;
    private int openTx;
    private boolean attachedWhileTxOpen;
    private boolean recordedWhileTxOpen;
    private StatementParseRecords parseRecords;

    @BeforeEach
    void setUp() {
        gmailEngine = mock(GmailEngine.class);
        GmailSyncCursorService cursorService = mock(GmailSyncCursorService.class);
        GmailSenderRepository senderRepository = mock(GmailSenderRepository.class);
        processedMessageRepository = mock(GmailProcessedMessageRepository.class);
        GmailBackfillDemandRepository backfillDemandRepository = mock(GmailBackfillDemandRepository.class);
        GmailIngestProperties ingestProperties = mock(GmailIngestProperties.class);
        reconciliation = mock(StatementReconciliationService.class);
        attacher = mock(StatementFileAttacher.class);
        PlatformTransactionManager tm = mock(PlatformTransactionManager.class);
        when(tm.getTransaction(any())).thenAnswer(i -> {
            openTx++;
            return mock(TransactionStatus.class);
        });
        doAnswer(i -> { openTx--; return null; }).when(tm).commit(any());
        doAnswer(i -> { openTx--; return null; }).when(tm).rollback(any());
        doAnswer(i -> { attachedWhileTxOpen |= openTx > 0; return null; }).when(attacher).attach(any(), any(), any(), any());
        doAnswer(i -> { attachedWhileTxOpen |= openTx > 0; return null; }).when(attacher)
                .attachIfStoredStatementLacksFile(any(), any(), any(), any(), any());

        service = new GmailIngestionService(gmailEngine, cursorService, senderRepository, mock(AccountResolver.class),
                mock(GmailTransactionWriter.class), mock(GeminiExtractor.class), processedMessageRepository,
                backfillDemandRepository, ingestProperties, reconciliation, mock(CategorizationService.class),
                mock(TransactionRepository.class), tm, mock(ParkedMessageSettler.class));
        ReflectionTestUtils.setField(service, "emailClassifier", new EmailClassifier());
        ReflectionTestUtils.setField(service, "observabilityMetrics", new ObservabilityMetrics(new SimpleMeterRegistry()));
        ReflectionTestUtils.setField(service, "statementFileAttacher", attacher);
        parseRecords = mock(StatementParseRecords.class);
        doAnswer(i -> { recordedWhileTxOpen |= openTx > 0; return null; }).when(parseRecords).saveQuietly(any(), any(), any());
        service.setParseRecords(parseRecords);

        user = new User();
        user.setId(UUID.randomUUID());
        connection = new GmailConnection();
        connection.setId(UUID.randomUUID());
        connection.setUser(user);
        connection.setEmail("user@example.com");
        GmailSender sender = new GmailSender();
        sender.setId(UUID.randomUUID());
        sender.setUser(user);
        sender.setSenderAddress("hdfcbank.net");
        sender.setEnabled(true);
        when(senderRepository.findByUserIdAndEnabledTrue(user.getId())).thenReturn(List.of(sender));
        GmailSyncCursor cursor = new GmailSyncCursor();
        cursor.setUser(user);
        cursor.setConnection(connection);
        cursor.setSender(sender);
        cursor.setLastListedAt(Instant.now().minusSeconds(86400));
        cursor.setEarliestCoveredAt(Instant.now().minusSeconds(86400));
        when(cursorService.getOrSeedCursors(connection)).thenReturn(List.of(cursor));
        when(ingestProperties.getProcessBudget()).thenReturn(100);
        when(ingestProperties.getBackfillProcessBudget()).thenReturn(500);
        when(ingestProperties.getOverlapLapMinutes()).thenReturn(15);
        when(ingestProperties.getStaleProcessingMinutes()).thenReturn(60);
        when(ingestProperties.getRetryMaxAttempts()).thenReturn(5);
        when(backfillDemandRepository.findById(user.getId())).thenReturn(Optional.empty());
    }

    private GmailMessage run() {
        GmailMessage m = new GmailMessage("s1", Instant.now(), "alerts@hdfcbank.net", "Statement", "s", "attached", null,
                List.of(new GmailAttachment("att", "stmt.pdf", "application/pdf", new byte[]{1})));
        when(gmailEngine.listMessageIds(eq(connection), anyString())).thenReturn(List.of("s1"));
        when(processedMessageRepository.findExistingMessageIds(eq(connection.getId()), anyCollection())).thenReturn(List.of());
        GmailProcessedMessage row = new GmailProcessedMessage();
        row.setId(UUID.randomUUID());
        row.setConnection(connection);
        row.setUser(user);
        row.setGmailMessageId("s1");
        row.setStatus(GmailProcessedStatus.DISCOVERED);
        row.setDiscoveredAt(Instant.now());
        when(processedMessageRepository.countByConnectionIdAndStatusIn(eq(connection.getId()), anyCollection())).thenReturn(1L, 0L);
        when(processedMessageRepository.findPendingForDrain(eq(connection.getId()), any(), any())).thenReturn(List.of(row));
        when(processedMessageRepository.findById(row.getId())).thenReturn(Optional.of(row));
        when(gmailEngine.fetchMessageDetails(connection, "s1")).thenReturn(m);
        service.syncConnection(connection);
        return m;
    }

    private StatementFileHandoff handoff(UUID forStatement) {
        return new StatementFileHandoff(user.getId(), accountId, forStatement, "sha", bytes, "stmt.pdf", 2, "{\"snap\":1}");
    }

    @Test
    void aNewStatementsRecordIsWrittenAfterTheTransactionCommits() {
        when(reconciliation.reconcile(eq(connection), any())).thenReturn(new ReconSummary(0, 0, 0).withFileHandoff(handoff(statementId)));

        run();

        verify(parseRecords).saveQuietly(statementId, 2, "{\"snap\":1}");
        assertThat(recordedWhileTxOpen).isFalse();
    }

    @Test
    void anAlreadyIngestedStatementWritesNoRecord() {
        when(reconciliation.reconcile(eq(connection), any())).thenReturn(new ReconSummary(0, 0, 0).withFileHandoff(handoff(null)));

        run();

        verify(parseRecords, never()).saveQuietly(any(), any(), any());
    }

    @Test
    void nothingIsWrittenWhenReconcileThrowsAndTheTransactionRollsBack() {
        when(reconciliation.reconcile(eq(connection), any())).thenThrow(new IllegalStateException("rolled back"));

        run();

        verify(parseRecords, never()).saveQuietly(any(), any(), any());
    }

    @Test
    void aSummaryWithoutAHandoffWritesNothing() {
        when(reconciliation.reconcile(eq(connection), any())).thenReturn(new ReconSummary(0, 0, 1));

        run();

        verify(parseRecords, never()).saveQuietly(any(), any(), any());
    }

    @Test
    void withoutARecordsServiceTheMessageStillProcessesAndItsFileIsKept() {
        service.setParseRecords(null);
        when(reconciliation.reconcile(eq(connection), any())).thenReturn(new ReconSummary(0, 0, 0).withFileHandoff(handoff(statementId)));

        run();

        verify(attacher).attach(user.getId(), statementId, bytes, "stmt.pdf");
    }

    @Test
    void theRecordIsWrittenBeforeTheFileIsAttachedAndEitherWayTheFileStillAttaches() {
        when(reconciliation.reconcile(eq(connection), any())).thenReturn(new ReconSummary(0, 0, 0).withFileHandoff(handoff(statementId)));

        run();

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(parseRecords, attacher);
        order.verify(parseRecords).saveQuietly(statementId, 2, "{\"snap\":1}");
        order.verify(attacher).attach(user.getId(), statementId, bytes, "stmt.pdf");
    }
}
