package com.financeos.gmail.ingest;

import com.financeos.domain.statement.StatementCoverage;
import com.financeos.domain.transaction.Transaction;
import com.financeos.domain.transaction.match.AlertMatcher;
import org.mockito.ArgumentCaptor;
import com.financeos.core.observability.ObservabilityMetrics;
import com.financeos.domain.categorization.CategorizationService;
import com.financeos.domain.transaction.TransactionRepository;
import com.financeos.domain.user.User;
import com.financeos.domain.account.Account;
import com.financeos.gmail.extract.Direction;
import com.financeos.gmail.extract.ExtractedTransaction;
import com.financeos.gmail.extract.Tier;
import com.financeos.gmail.extract.TransactionExtractor;
import com.financeos.gmail.domain.*;
import com.financeos.gmail.engine.GmailEngine;
import com.financeos.gmail.ingest.gemini.GeminiExtractionResult;
import com.financeos.gmail.ingest.gemini.GeminiExtractor;
import com.financeos.gmail.internal.GmailMessage;
import com.financeos.gmail.reconcile.StatementReconciliationService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** Statement coverage in {@link GmailIngestionService}: the pre-AI shortcut and statement-first batch ordering. */
class GmailIngestionStatementCoverageTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 20);

    private GmailEngine gmailEngine;
    private GeminiExtractor geminiExtractor;
    private GmailProcessedMessageRepository processedMessageRepository;
    private StatementReconciliationService statementReconciliationService;
    private TransactionRepository transactionRepository;
    private AccountResolver accountResolver;
    private GmailTransactionWriter writer;
    private int openTx;
    private int maxOpenTx;
    private GmailIngestionService service;
    private SimpleMeterRegistry registry;
    private TransactionExtractor engine;
    private StatementCoverage coverage;
    private AlertMatcher alertMatcher;
    private GmailSender sender;

    private GmailConnection connection;
    private User user;

    @BeforeEach
    void setUp() {
        writer = mock(GmailTransactionWriter.class);
        gmailEngine = mock(GmailEngine.class);
        GmailSyncCursorService cursorService = mock(GmailSyncCursorService.class);
        GmailSenderRepository senderRepository = mock(GmailSenderRepository.class);
        accountResolver = mock(AccountResolver.class);
        geminiExtractor = mock(GeminiExtractor.class);
        processedMessageRepository = mock(GmailProcessedMessageRepository.class);
        GmailBackfillDemandRepository backfillDemandRepository = mock(GmailBackfillDemandRepository.class);
        GmailIngestProperties ingestProperties = mock(GmailIngestProperties.class);
        statementReconciliationService = mock(StatementReconciliationService.class);
        transactionRepository = mock(TransactionRepository.class);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any())).thenAnswer(i -> {
            openTx++;
            maxOpenTx = Math.max(maxOpenTx, openTx);
            return mock(TransactionStatus.class);
        });
        doAnswer(i -> { openTx--; return null; }).when(transactionManager).commit(any());
        doAnswer(i -> { openTx--; return null; }).when(transactionManager).rollback(any());

        coverage = mock(StatementCoverage.class);
        alertMatcher = mock(AlertMatcher.class);
        service = new GmailIngestionService(gmailEngine, cursorService, senderRepository, accountResolver,
                writer, geminiExtractor, processedMessageRepository, backfillDemandRepository,
                ingestProperties, statementReconciliationService, mock(CategorizationService.class), transactionRepository,
                transactionManager, mock(ParkedMessageSettler.class));
        engine = mock(TransactionExtractor.class);
        registry = new SimpleMeterRegistry();
        ReflectionTestUtils.setField(service, "transactionExtractor", engine);
        ReflectionTestUtils.setField(service, "statementCoverage", coverage);
        ReflectionTestUtils.setField(service, "alertMatcher", alertMatcher);
        ReflectionTestUtils.setField(service, "observabilityMetrics", new ObservabilityMetrics(registry));

        user = new User();
        user.setId(UUID.randomUUID());
        connection = new GmailConnection();
        connection.setId(UUID.randomUUID());
        connection.setUser(user);
        connection.setEmail("user@example.com");

        sender = new GmailSender();
        sender.setId(UUID.randomUUID());
        sender.setUser(user);
        sender.setSenderAddress("hdfcbank.net");
        sender.setEnabled(true);
        List<GmailSender> senders = new java.util.ArrayList<>(List.of(sender));
        for (String d : List.of("cred.club", "amazonpay.in", "icicibank.com", "paytm.com")) {
            GmailSender extra = new GmailSender();
            extra.setId(UUID.randomUUID());
            extra.setUser(user);
            extra.setSenderAddress(d);
            extra.setEnabled(true);
            senders.add(extra);
        }
        when(senderRepository.findByUserIdAndEnabledTrue(user.getId())).thenReturn(senders);

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


    private static GmailMessage msg(String id, String from) {
        return new GmailMessage(id, Instant.now(), "Sender <" + from + ">", "Alert", "s", "body", null, List.of());
    }

    private GmailProcessedMessage rowFor(String id) {
        GmailProcessedMessage row = new GmailProcessedMessage();
        row.setId(UUID.randomUUID());
        row.setConnection(connection);
        row.setUser(user);
        row.setGmailMessageId(id);
        row.setStatus(GmailProcessedStatus.DISCOVERED);
        row.setDiscoveredAt(Instant.now());
        when(processedMessageRepository.findById(row.getId())).thenReturn(Optional.of(row));
        return row;
    }

    private void runRows(List<GmailProcessedMessage> rows) {
        runRowsWithSummary(rows);
    }

    private SyncSummary runRowsWithSummary(List<GmailProcessedMessage> rows) {
        when(gmailEngine.listMessageIds(eq(connection), anyString())).thenReturn(List.of());
        when(processedMessageRepository.countByConnectionIdAndStatusIn(eq(connection.getId()), anyCollection())).thenReturn(1L, 0L);
        when(processedMessageRepository.findPendingForDrain(eq(connection.getId()), any(), any())).thenReturn(rows);
        return service.syncConnection(connection);
    }

    private ExtractedTransaction engineRead(Tier tier, String last4, LocalDate date) {
        return new ExtractedTransaction(new BigDecimal("725.00"), "INR", Direction.DEBIT, date, "CAFE", last4, tier, "hdfc-card", 0.95);
    }

    private Account account() {
        Account a = new Account();
        a.setId(UUID.randomUUID());
        a.setName("Card");
        a.setIngestFromDate(LocalDate.of(2026, 1, 1));
        return a;
    }

    private static GmailProcessedMessage written() {
        GmailProcessedMessage w = new GmailProcessedMessage();
        w.setStatus(GmailProcessedStatus.SKIPPED_NOT_TRANSACTION);
        return w;
    }

    private GmailProcessedMessage alertRun(Optional<ExtractedTransaction> eng, boolean attaches) {
        GmailMessage m = msg("a1", "alerts@hdfcbank.net");
        GmailProcessedMessage row = rowFor("a1");
        when(gmailEngine.fetchMessageDetails(connection, "a1")).thenReturn(m);
        when(engine.extract(m)).thenReturn(eng);
        Account acc = account();
        when(accountResolver.resolve(any(), any(), any())).thenReturn(Optional.of(new AccountResolver.ResolvedCard(acc, null)));
        when(alertMatcher.attachIfMatched(any())).thenReturn(attaches
                ? Optional.of(new AlertMatcher.Attached(new Transaction(), AlertMatcher.Kind.STATEMENT_LINE,
                        "Attached to an existing transaction (statement line 20/10/2026)"))
                : Optional.empty());
        when(geminiExtractor.extract(m)).thenReturn(new GeminiExtractionResult(true, new BigDecimal("725.00"), "INR", "DEBIT", DAY,
                "Cafe", "4821", 0.9, true, null).withProvider("gemini/x"));
        when(writer.writeTransaction(any(), any(), any(), any(), any(), any())).thenReturn(written());
        runRows(List.of(row));
        assertThat(row.getStatus()).as("status/error: " + row.getError()).isNotEqualTo(GmailProcessedStatus.FAILED_RETRYABLE);
        return row;
    }

    @Test
    void templateTierThatAttachesSkipsGemini() {
        GmailProcessedMessage row = alertRun(Optional.of(engineRead(Tier.TEMPLATE, "4821", DAY)), true);
        ArgumentCaptor<AlertMatcher.Alert> alert = ArgumentCaptor.forClass(AlertMatcher.Alert.class);
        verify(alertMatcher).attachIfMatched(alert.capture());
        assertThat(alert.getValue().amount()).isEqualByComparingTo("725.00");
        assertThat(alert.getValue().date()).isEqualTo(DAY);
        assertThat(alert.getValue().senderAddress()).isEqualTo("alerts@hdfcbank.net");
        assertThat(row.getPipelineKind()).isEqualTo("ALERT");
        verify(writer).recordAttached(eq(row), any(), any());
        verify(geminiExtractor, never()).extract(any());
        verify(writer, never()).writeTransaction(any(), any(), any(), any(), any(), any());
    }

    @Test
    void templateTierThatDoesNotAttachFallsThroughToGemini() {
        alertRun(Optional.of(engineRead(Tier.TEMPLATE, "4821", DAY)), false);
        verify(geminiExtractor).extract(any());
        verify(writer).writeTransaction(any(), any(), any(), any(), any(), any());
    }

    @Test
    void genericTierCallsGeminiWithoutThePreAiMatch() {
        alertRun(Optional.of(engineRead(Tier.GENERIC, "4821", DAY)), true);
        verify(geminiExtractor).extract(any());
        verify(alertMatcher, never()).attachIfMatched(any());
        verify(writer).writeTransaction(any(), any(), any(), any(), any(), any());
    }

    @Test
    void noEngineResultCallsGemini() {
        alertRun(Optional.empty(), true);
        verify(geminiExtractor).extract(any());
        verify(writer).writeTransaction(any(), any(), any(), any(), any(), any());
    }

    @Test
    void templateWithAnUnknownCurrencySkipsThePreAiMatchAndCallsGemini() {
        ExtractedTransaction unsure = new ExtractedTransaction(new BigDecimal("20.00"), null, Direction.DEBIT, DAY, "GITHUB",
                "4821", Tier.TEMPLATE, "hdfc-card", 0.95);
        alertRun(Optional.of(unsure), true);
        verify(alertMatcher, never()).attachIfMatched(any());
        verify(geminiExtractor).extract(any());
    }

    @Test
    void templateWithoutLast4FallsThroughToGemini() {
        alertRun(Optional.of(engineRead(Tier.TEMPLATE, null, DAY)), true);
        verify(alertMatcher, never()).attachIfMatched(any());
        verify(geminiExtractor).extract(any());
    }

    @Test
    void theWriterGetsTheMailsSenderSendTimeAndReferences() {
        GmailMessage m = new GmailMessage("a2", Instant.parse("2026-10-20T05:00:00Z"), "Bank <alerts@hdfcbank.net>", "Alert", "s",
                "Rs 725.00 debited. UPI Ref No 412345678901", null, List.of());
        GmailProcessedMessage row = rowFor("a2");
        when(gmailEngine.fetchMessageDetails(connection, "a2")).thenReturn(m);
        when(engine.extract(m)).thenReturn(Optional.empty());
        when(accountResolver.resolve(any(), any(), any())).thenReturn(Optional.of(new AccountResolver.ResolvedCard(account(), null)));
        when(geminiExtractor.extract(m)).thenReturn(new GeminiExtractionResult(true, new BigDecimal("725.00"), "INR", "DEBIT", DAY,
                "Cafe", "4821", 0.9, true, null).withProvider("gemini/x"));
        ArgumentCaptor<GmailTransactionWriter.EmailContext> email = ArgumentCaptor.forClass(GmailTransactionWriter.EmailContext.class);
        when(writer.writeTransaction(any(), any(), any(), any(), any(), email.capture())).thenReturn(written());

        runRows(List.of(row));

        assertThat(email.getValue().senderAddress()).isEqualTo("alerts@hdfcbank.net");
        assertThat(email.getValue().sentAt()).isEqualTo(Instant.parse("2026-10-20T05:00:00Z"));
        assertThat(email.getValue().refs()).containsExactly("412345678901");
    }

    @Test
    void anAlertTheWriterAttachedIsNotCountedOrCategorisedAsNew() {
        GmailMessage m = msg("a3", "alerts@hdfcbank.net");
        GmailProcessedMessage row = rowFor("a3");
        when(gmailEngine.fetchMessageDetails(connection, "a3")).thenReturn(m);
        when(engine.extract(m)).thenReturn(Optional.empty());
        when(accountResolver.resolve(any(), any(), any())).thenReturn(Optional.of(new AccountResolver.ResolvedCard(account(), null)));
        when(geminiExtractor.extract(m)).thenReturn(new GeminiExtractionResult(true, new BigDecimal("725.00"), "INR", "DEBIT", DAY,
                "Cafe", "4821", 0.9, true, null).withProvider("gemini/x"));
        GmailProcessedMessage attached = new GmailProcessedMessage();
        attached.setStatus(GmailProcessedStatus.SKIPPED_DUPLICATE);
        Transaction existing = new Transaction();
        existing.setId(UUID.randomUUID());
        attached.setTransaction(existing);
        when(writer.writeTransaction(any(), any(), any(), any(), any(), any())).thenReturn(attached);

        SyncSummary summary = runRowsWithSummary(List.of(row));

        assertThat(row.getStatus()).isEqualTo(GmailProcessedStatus.SKIPPED_DUPLICATE);
        assertThat(row.getTransaction()).isSameAs(existing);
        assertThat(summary.created()).isZero();
    }

    @Test
    void statementMailIsProcessedBeforeAlertInSameBatch() {
        GmailMessage alertMsg = msg("alert", "alerts@hdfcbank.net");
        GmailMessage stmtMsg = new GmailMessage("stmt", Instant.now(), "Sender <alerts@hdfcbank.net>", "Statement", "s", "body", null,
                List.of(new com.financeos.gmail.internal.GmailAttachment("att", "stmt.pdf", "application/pdf", new byte[0])));
        GmailProcessedMessage alertRow = rowFor("alert");
        GmailProcessedMessage stmtRow = rowFor("stmt");
        stmtRow.setPipelineKind("STATEMENT");
        when(gmailEngine.fetchMessageDetails(connection, "alert")).thenReturn(alertMsg);
        when(gmailEngine.fetchMessageDetails(connection, "stmt")).thenReturn(stmtMsg);
        when(geminiExtractor.extract(any())).thenReturn(GeminiExtractionResult.notTransaction().withProvider("gemini/x"));
        when(statementReconciliationService.reconcile(any(), any())).thenReturn(new com.financeos.gmail.reconcile.ReconSummary(0, 0, 0));

        runRows(List.of(alertRow, stmtRow));

        var order = inOrder(gmailEngine);
        order.verify(gmailEngine).fetchMessageDetails(connection, "stmt");
        order.verify(gmailEngine).fetchMessageDetails(connection, "alert");
        verify(coverage).invalidateAll();
    }

    @Test
    void aFreshRowWithAnAttachmentIsProcessedBeforeAnEarlierAlert() {
        GmailMessage alertMsg = msg("alert", "alerts@hdfcbank.net");
        GmailMessage stmtMsg = new GmailMessage("stmt", Instant.now(), "Sender <alerts@hdfcbank.net>", "Statement", "s", "body", null,
                List.of(new com.financeos.gmail.internal.GmailAttachment("att", "stmt.pdf", "application/pdf", new byte[0])));
        GmailProcessedMessage alertRow = rowFor("alert");
        GmailProcessedMessage stmtRow = rowFor("stmt");
        stmtRow.setHasAttachment(true);
        when(gmailEngine.fetchMessageDetails(connection, "alert")).thenReturn(alertMsg);
        when(gmailEngine.fetchMessageDetails(connection, "stmt")).thenReturn(stmtMsg);
        when(geminiExtractor.extract(any())).thenReturn(GeminiExtractionResult.notTransaction().withProvider("gemini/x"));
        when(statementReconciliationService.reconcile(any(), any())).thenReturn(new com.financeos.gmail.reconcile.ReconSummary(0, 0, 0));

        runRows(List.of(alertRow, stmtRow));

        var order = inOrder(gmailEngine);
        order.verify(gmailEngine).fetchMessageDetails(connection, "stmt");
        order.verify(gmailEngine).fetchMessageDetails(connection, "alert");
    }

    @Test
    void discoveryMarksTheNewMailsGmailListsWithAnAttachment() {
        when(gmailEngine.listMessageIds(eq(connection), anyString())).thenAnswer(inv -> {
            String q = inv.getArgument(1);
            return q.endsWith(" has:attachment") ? List.of("x2") : List.of("x1", "x2");
        });
        when(processedMessageRepository.findExistingMessageIds(any(), any())).thenReturn(List.of());
        when(processedMessageRepository.countByConnectionIdAndStatusIn(eq(connection.getId()), anyCollection())).thenReturn(0L);
        when(processedMessageRepository.findPendingForDrain(eq(connection.getId()), any(), any())).thenReturn(List.of());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<GmailProcessedMessage>> saved = ArgumentCaptor.forClass(List.class);

        service.syncConnection(connection);

        verify(processedMessageRepository).saveAll(saved.capture());
        assertThat(saved.getValue()).extracting(GmailProcessedMessage::getGmailMessageId, GmailProcessedMessage::isHasAttachment)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("x1", false), org.assertj.core.groups.Tuple.tuple("x2", true));
        // one listing for the window, one for its attachments
        verify(gmailEngine, times(2)).listMessageIds(eq(connection), anyString());
    }

    @Test
    void discoveryWithNothingNewAsksNoAttachmentListing() {
        when(gmailEngine.listMessageIds(eq(connection), anyString())).thenReturn(List.of("x1"));
        when(processedMessageRepository.findExistingMessageIds(any(), any())).thenReturn(List.of("x1"));
        when(processedMessageRepository.countByConnectionIdAndStatusIn(eq(connection.getId()), anyCollection())).thenReturn(0L);
        when(processedMessageRepository.findPendingForDrain(eq(connection.getId()), any(), any())).thenReturn(List.of());

        service.syncConnection(connection);

        verify(gmailEngine, times(1)).listMessageIds(eq(connection), anyString());
    }
}
