package com.financeos.gmail.ingest;

import com.financeos.domain.categorization.CategorizationService;
import com.financeos.domain.transaction.TransactionRepository;
import com.financeos.domain.user.User;
import com.financeos.gmail.domain.*;
import com.financeos.gmail.engine.GmailEngine;
import com.financeos.gmail.ingest.gemini.GeminiExtractionResult;
import com.financeos.gmail.ingest.gemini.GeminiExtractor;
import com.financeos.gmail.internal.GmailMessage;
import com.financeos.gmail.reconcile.StatementReconciliationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** The document-handler extension point: a handled mail never reaches the AI read, any other mail keeps the old path. */
class GmailIngestionDocumentHandlerTest {

    private GmailEngine gmailEngine;
    private GeminiExtractor geminiExtractor;
    private GmailProcessedMessageRepository processedMessageRepository;
    private GmailDocumentHandler handler;
    private GmailIngestionService service;
    private GmailConnection connection;
    private User user;

    @BeforeEach
    void setUp() {
        gmailEngine = mock(GmailEngine.class);
        geminiExtractor = mock(GeminiExtractor.class);
        processedMessageRepository = mock(GmailProcessedMessageRepository.class);
        GmailSyncCursorService cursorService = mock(GmailSyncCursorService.class);
        GmailSenderRepository senderRepository = mock(GmailSenderRepository.class);
        GmailBackfillDemandRepository demandRepository = mock(GmailBackfillDemandRepository.class);
        GmailIngestProperties props = mock(GmailIngestProperties.class);
        PlatformTransactionManager tm = mock(PlatformTransactionManager.class);
        when(tm.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        service = new GmailIngestionService(gmailEngine, cursorService, senderRepository, mock(AccountResolver.class),
                mock(GmailTransactionWriter.class), geminiExtractor, processedMessageRepository, demandRepository, props,
                mock(StatementReconciliationService.class), mock(CategorizationService.class),
                mock(TransactionRepository.class), tm, mock(ParkedMessageSettler.class));
        handler = mock(GmailDocumentHandler.class);
        ReflectionTestUtils.setField(service, "documentHandlers", List.of(handler));

        user = new User();
        user.setId(UUID.randomUUID());
        connection = new GmailConnection();
        connection.setId(UUID.randomUUID());
        connection.setUser(user);
        connection.setEmail("user@example.com");

        GmailSender npsSender = sender("nps-alerts@proteantech.in");
        GmailSender bankSender = sender("alerts@bank.com");
        when(senderRepository.findByUserIdAndEnabledTrue(user.getId())).thenReturn(List.of(npsSender, bankSender));
        GmailSyncCursor cursor = new GmailSyncCursor();
        cursor.setUser(user);
        cursor.setConnection(connection);
        cursor.setSender(bankSender);
        cursor.setLastListedAt(Instant.now().minusSeconds(86400));
        cursor.setEarliestCoveredAt(Instant.now().minusSeconds(86400));
        when(cursorService.getOrSeedCursors(connection)).thenReturn(List.of(cursor));
        when(props.getProcessBudget()).thenReturn(100);
        when(props.getBackfillProcessBudget()).thenReturn(500);
        when(props.getOverlapLapMinutes()).thenReturn(15);
        when(props.getStaleProcessingMinutes()).thenReturn(60);
        when(props.getRetryMaxAttempts()).thenReturn(5);
        when(demandRepository.findById(user.getId())).thenReturn(Optional.empty());
    }

    private GmailSender sender(String address) {
        GmailSender s = new GmailSender();
        s.setId(UUID.randomUUID());
        s.setUser(user);
        s.setSenderAddress(address);
        s.setEnabled(true);
        return s;
    }

    private GmailProcessedMessage drain(String messageId, GmailMessage message) {
        when(gmailEngine.listMessageIds(eq(connection), anyString())).thenReturn(List.of(messageId));
        when(processedMessageRepository.findExistingMessageIds(eq(connection.getId()), anyCollection())).thenReturn(List.of());
        GmailProcessedMessage row = new GmailProcessedMessage();
        row.setId(UUID.randomUUID());
        row.setConnection(connection);
        row.setUser(user);
        row.setGmailMessageId(messageId);
        row.setStatus(GmailProcessedStatus.DISCOVERED);
        row.setDiscoveredAt(Instant.now());
        when(processedMessageRepository.countByConnectionIdAndStatusIn(eq(connection.getId()), anyCollection())).thenReturn(1L, 0L);
        when(processedMessageRepository.findPendingForDrain(eq(connection.getId()), any(), any())).thenReturn(List.of(row));
        when(processedMessageRepository.findById(row.getId())).thenReturn(Optional.of(row));
        when(gmailEngine.fetchMessageDetails(connection, messageId)).thenReturn(message);
        return row;
    }

    @Test
    void aMailAHandlerTakesIsNeverReadByTheAiAndKeepsTheHandlersStatus() {
        GmailMessage message = new GmailMessage("nps-1", Instant.now(), "NPS <nps-alerts@proteantech.in>",
                "Protean - Intimation of Contribution Credit", "s", "body", null, List.of());
        GmailProcessedMessage row = drain("nps-1", message);
        when(handler.supports("nps-alerts@proteantech.in", message)).thenReturn(true);
        when(handler.handle(connection, message)).thenReturn(GmailDocumentHandler.Handled.of(GmailProcessedStatus.NPS_ENTRY));

        SyncSummary summary = service.syncConnection(connection);

        assertThat(row.getStatus()).isEqualTo(GmailProcessedStatus.NPS_ENTRY);
        assertThat(summary.processed()).isEqualTo(1);
        assertThat(summary.created()).isZero();
        verifyNoInteractions(geminiExtractor);
    }

    @Test
    void aMailNoHandlerTakesFollowsTheOldPath() {
        GmailMessage message = new GmailMessage("bank-1", Instant.now(), "alerts@bank.com", "Alert", "s", "body", null, List.of());
        GmailProcessedMessage row = drain("bank-1", message);
        when(handler.supports("alerts@bank.com", message)).thenReturn(false);
        when(geminiExtractor.extract(message)).thenReturn(GeminiExtractionResult.notTransaction());

        service.syncConnection(connection);

        assertThat(row.getStatus()).isEqualTo(GmailProcessedStatus.SKIPPED_NOT_TRANSACTION);
        verify(geminiExtractor).extract(message);
        verify(handler, never()).handle(any(), any());
    }

    @Test
    void aHandlerFailureRetriesTheMailLater() {
        GmailMessage message = new GmailMessage("nps-2", Instant.now(), "nps-alerts@proteantech.in", "s", "s", "body", null, List.of());
        GmailProcessedMessage row = drain("nps-2", message);
        when(handler.supports("nps-alerts@proteantech.in", message)).thenReturn(true);
        when(handler.handle(connection, message)).thenThrow(new IllegalStateException("boom"));

        service.syncConnection(connection);

        assertThat(row.getStatus()).isEqualTo(GmailProcessedStatus.FAILED_RETRYABLE);
        verifyNoInteractions(geminiExtractor);
    }
}
