package com.financeos.gmail.ingest;

import com.financeos.core.observability.ObservabilityMetrics;
import com.financeos.domain.categorization.CategorizationService;
import com.financeos.domain.transaction.TransactionRepository;
import com.financeos.domain.user.User;
import com.financeos.gmail.classify.ClassificationResult;
import com.financeos.gmail.classify.EmailClassifier;
import com.financeos.gmail.domain.*;
import com.financeos.gmail.engine.GmailEngine;
import com.financeos.gmail.ingest.gemini.GeminiExtractionResult;
import com.financeos.gmail.ingest.gemini.GeminiExtractor;
import com.financeos.gmail.internal.GmailAttachment;
import com.financeos.gmail.internal.GmailMessage;
import com.financeos.gmail.reconcile.ReconSummary;
import com.financeos.gmail.reconcile.StatementReconciliationService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** Shadow classifier wiring in {@link GmailIngestionService}: fields stored, pipeline_kind per path, outcome unchanged. */
class GmailIngestionClassifierShadowTest {

    private static final String ALERT_BODY = "Rs. 725.00 has been debited from your HDFC Bank Credit Card ending 4821 towards BLUE LEAF CAFE.";

    private GmailEngine gmailEngine;
    private GeminiExtractor geminiExtractor;
    private GmailProcessedMessageRepository processedMessageRepository;
    private StatementReconciliationService statementReconciliationService;
    private TransactionRepository transactionRepository;
    private AccountResolver accountResolver;
    private GmailIngestionService service;
    private SimpleMeterRegistry registry;

    private GmailConnection connection;
    private User user;

    @BeforeEach
    void setUp() {
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
        when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));

        service = new GmailIngestionService(gmailEngine, cursorService, senderRepository, accountResolver,
                mock(GmailTransactionWriter.class), geminiExtractor, processedMessageRepository, backfillDemandRepository,
                ingestProperties, statementReconciliationService, mock(CategorizationService.class), transactionRepository,
                transactionManager, mock(ParkedMessageSettler.class));
        registry = new SimpleMeterRegistry();
        ReflectionTestUtils.setField(service, "emailClassifier", new EmailClassifier());
        ReflectionTestUtils.setField(service, "observabilityMetrics", new ObservabilityMetrics(registry));

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

    private GmailProcessedMessage run(GmailMessage message) {
        when(gmailEngine.listMessageIds(eq(connection), anyString())).thenReturn(List.of(message.messageId()));
        when(processedMessageRepository.findExistingMessageIds(eq(connection.getId()), anyCollection())).thenReturn(List.of());
        GmailProcessedMessage row = new GmailProcessedMessage();
        row.setId(UUID.randomUUID());
        row.setConnection(connection);
        row.setUser(user);
        row.setGmailMessageId(message.messageId());
        row.setStatus(GmailProcessedStatus.DISCOVERED);
        row.setDiscoveredAt(Instant.now());
        when(processedMessageRepository.countByConnectionIdAndStatusIn(eq(connection.getId()), anyCollection())).thenReturn(1L, 0L);
        when(processedMessageRepository.findPendingForDrain(eq(connection.getId()), any(), any())).thenReturn(List.of(row));
        when(processedMessageRepository.findById(row.getId())).thenReturn(Optional.of(row));
        when(gmailEngine.fetchMessageDetails(connection, message.messageId())).thenReturn(message);
        service.syncConnection(connection);
        return row;
    }

    private static GmailMessage alert(String id, String from) {
        return new GmailMessage(id, Instant.now(), "HDFC <" + from + ">", "Alert", "s", ALERT_BODY, null, List.of());
    }

    private double compare(String classified, String pipeline) {
        var counter = registry.find("financeos.gmail.classifier.compare").tag("classified", classified).tag("pipeline", pipeline).counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    void alertPathStoresClassificationAndPipelineAlert() {
        GmailMessage m = alert("a1", "alerts@hdfcbank.net");
        when(geminiExtractor.extract(m)).thenReturn(GeminiExtractionResult.success(
                new com.financeos.gmail.ingest.gemini.ExtractedTransaction(true, new java.math.BigDecimal("725.00"), "INR", "DEBIT",
                        java.time.LocalDate.now().toString(), "BLUE LEAF CAFE", "4821", 0.9), java.time.LocalDate.now()));
        when(accountResolver.resolve(any(), any(), any())).thenReturn(Optional.empty());

        GmailProcessedMessage row = run(m);

        assertThat(row.getClassifiedKind()).isEqualTo("ALERT");
        assertThat(row.getClassifiedSubtype()).isEqualTo("DEBIT");
        assertThat(row.getClassifiedConfidence()).isEqualTo("HIGH");
        assertThat(row.getClassifiedBank()).isEqualTo("hdfc");
        assertThat(row.getClassifiedSignals()).contains("verb:debited");
        assertThat(row.getClassifierVersion()).isEqualTo(EmailClassifier.VERSION);
        assertThat(row.getPipelineKind()).isEqualTo("ALERT");
        assertThat(registry.find("financeos.gmail.classified").tag("kind", "ALERT").tag("subtype", "DEBIT").tag("confidence", "HIGH").counter().count()).isEqualTo(1.0);
        assertThat(compare("ALERT", "ALERT")).isEqualTo(1.0);
    }

    @Test
    void notTransactionPathStoresClassificationAndPipelineOther() {
        GmailMessage m = new GmailMessage("n1", Instant.now(), "alerts@hdfcbank.net", "Offers", "s",
                "Enjoy a pre-approved loan. Unsubscribe here.", null, List.of());
        when(geminiExtractor.extract(m)).thenReturn(GeminiExtractionResult.notTransaction());

        GmailProcessedMessage row = run(m);

        assertThat(row.getStatus()).isEqualTo(GmailProcessedStatus.SKIPPED_NOT_TRANSACTION);
        assertThat(row.getClassifiedKind()).isEqualTo("OTHER");
        assertThat(row.getClassifiedSubtype()).isEqualTo("PROMOTION");
        assertThat(row.getPipelineKind()).isEqualTo("OTHER");
        assertThat(compare("OTHER", "OTHER")).isEqualTo(1.0);
    }

    @Test
    void disagreementIsCountedByBothKinds() {
        GmailMessage m = alert("d1", "alerts@hdfcbank.net");
        when(geminiExtractor.extract(m)).thenReturn(GeminiExtractionResult.notTransaction());

        GmailProcessedMessage row = run(m);

        assertThat(row.getClassifiedKind()).isEqualTo("ALERT");
        assertThat(row.getPipelineKind()).isEqualTo("OTHER");
        assertThat(compare("ALERT", "OTHER")).isEqualTo(1.0);
    }

    @Test
    void statementPathStoresClassificationAndPipelineStatement() {
        GmailMessage m = new GmailMessage("s1", Instant.now(), "alerts@hdfcbank.net", "Credit Card Statement", "s",
                "Please find your statement attached.", null,
                List.of(new GmailAttachment("att", "stmt.pdf", "application/pdf", new byte[]{1})));
        when(statementReconciliationService.reconcile(eq(connection), eq(m))).thenReturn(new ReconSummary(0, 0, 0));

        GmailProcessedMessage row = run(m);

        assertThat(row.getClassifiedKind()).isEqualTo("STATEMENT");
        assertThat(row.getClassifiedSubtype()).isEqualTo("CARD_STATEMENT");
        assertThat(row.getPipelineKind()).isEqualTo("STATEMENT");
        assertThat(compare("STATEMENT", "STATEMENT")).isEqualTo(1.0);
        verifyNoInteractions(geminiExtractor);
    }

    @Test
    void senderDisabledPathStoresClassificationButNoPipelineKind() {
        GmailMessage m = alert("x1", "promo@elsewhere.example");

        GmailProcessedMessage row = run(m);

        assertThat(row.getStatus()).isEqualTo(GmailProcessedStatus.SKIPPED_SENDER_DISABLED);
        assertThat(row.getClassifiedKind()).isEqualTo("ALERT");
        assertThat(row.getClassifiedBank()).isNull();
        assertThat(row.getPipelineKind()).isNull();
        assertThat(registry.find("financeos.gmail.classified").counter().count()).isEqualTo(1.0);
        assertThat(registry.find("financeos.gmail.classifier.compare").counter()).isNull();
        verifyNoInteractions(geminiExtractor);
    }

    @Test
    void duplicatePathStoresClassificationButNoPipelineKind() {
        GmailMessage m = alert("dup1", "alerts@hdfcbank.net");
        when(transactionRepository.existsByUserIdAndSourceMessageId(user.getId(), "dup1")).thenReturn(true);

        GmailProcessedMessage row = run(m);

        assertThat(row.getStatus()).isEqualTo(GmailProcessedStatus.SKIPPED_DUPLICATE);
        assertThat(row.getClassifiedKind()).isEqualTo("ALERT");
        assertThat(row.getPipelineKind()).isNull();
    }

    @Test
    void extractionFailureKeepsClassificationAndNoPipelineKind() {
        GmailMessage m = alert("f1", "alerts@hdfcbank.net");
        when(geminiExtractor.extract(m)).thenReturn(GeminiExtractionResult.failure("boom"));

        GmailProcessedMessage row = run(m);

        assertThat(row.getStatus()).isEqualTo(GmailProcessedStatus.FAILED_RETRYABLE);
        assertThat(row.getClassifiedKind()).isEqualTo("ALERT");
        assertThat(row.getPipelineKind()).isNull();
    }

    @Test
    void classifierExceptionDoesNotChangeTheOutcome() {
        EmailClassifier broken = mock(EmailClassifier.class);
        when(broken.classify(any())).thenThrow(new IllegalStateException("classifier bug"));
        ReflectionTestUtils.setField(service, "emailClassifier", broken);
        GmailMessage m = new GmailMessage("e1", Instant.now(), "alerts@hdfcbank.net", "Hello", "s", "hi", null, List.of());
        when(geminiExtractor.extract(m)).thenReturn(GeminiExtractionResult.notTransaction());

        GmailProcessedMessage row = run(m);

        assertThat(row.getStatus()).isEqualTo(GmailProcessedStatus.SKIPPED_NOT_TRANSACTION);
        assertThat(row.getClassifiedKind()).isNull();
        assertThat(row.getClassifierVersion()).isNull();
        assertThat(row.getPipelineKind()).isEqualTo("OTHER");
        assertThat(registry.find("financeos.gmail.classified").counter()).isNull();
        assertThat(registry.find("financeos.gmail.classifier.compare").counter()).isNull();
    }

    @Test
    void classifierExceptionOnStatementPathStillReconciles() {
        EmailClassifier broken = mock(EmailClassifier.class);
        when(broken.classify(any())).thenThrow(new IllegalStateException("classifier bug"));
        ReflectionTestUtils.setField(service, "emailClassifier", broken);
        GmailMessage m = new GmailMessage("e2", Instant.now(), "alerts@hdfcbank.net", "Statement", "s", "attached", null,
                List.of(new GmailAttachment("att", "stmt.pdf", "application/pdf", new byte[]{1})));
        when(statementReconciliationService.reconcile(eq(connection), eq(m))).thenReturn(new ReconSummary(0, 0, 0));

        GmailProcessedMessage row = run(m);

        verify(statementReconciliationService).reconcile(connection, m);
        assertThat(row.getPipelineKind()).isEqualTo("STATEMENT");
        assertThat(row.getClassifiedKind()).isNull();
    }

    @Test
    void longSignalsAreTruncatedToColumnSize() {
        EmailClassifier chatty = mock(EmailClassifier.class);
        when(chatty.classify(any())).thenReturn(new ClassificationResult(
                com.financeos.gmail.classify.EmailKind.OTHER, com.financeos.gmail.classify.EmailSubtype.UNKNOWN,
                com.financeos.gmail.classify.ClassifierConfidence.LOW, null, List.of("s".repeat(900)), 1));
        ReflectionTestUtils.setField(service, "emailClassifier", chatty);
        GmailMessage m = new GmailMessage("t1", Instant.now(), "alerts@hdfcbank.net", "Hello", "s", "hi", null, List.of());
        when(geminiExtractor.extract(m)).thenReturn(GeminiExtractionResult.notTransaction());

        GmailProcessedMessage row = run(m);

        assertThat(row.getClassifiedSignals()).hasSize(500);
    }

    @Test
    void retryWithFailingClassifierDoesNotKeepThePreviousAttemptsValues() {
        EmailClassifier broken = mock(EmailClassifier.class);
        when(broken.classify(any())).thenThrow(new IllegalStateException("classifier bug"));
        ReflectionTestUtils.setField(service, "emailClassifier", broken);
        GmailMessage m = new GmailMessage("r1", Instant.now(), "alerts@hdfcbank.net", "Hello", "s", "hi", null, List.of());
        when(geminiExtractor.extract(m)).thenReturn(GeminiExtractionResult.failure("boom"));

        GmailProcessedMessage row = new GmailProcessedMessage();
        row.setClassifiedKind("ALERT");
        row.setClassifiedSubtype("DEBIT");
        row.setClassifiedConfidence("HIGH");
        row.setClassifiedBank("hdfc");
        row.setClassifiedSignals("amt");
        row.setClassifierVersion(1);
        row.setPipelineKind("ALERT");
        service.classifyInShadow(row, m);

        assertThat(row.getClassifiedKind()).isNull();
        assertThat(row.getClassifiedSubtype()).isNull();
        assertThat(row.getClassifiedConfidence()).isNull();
        assertThat(row.getClassifiedBank()).isNull();
        assertThat(row.getClassifiedSignals()).isNull();
        assertThat(row.getClassifierVersion()).isNull();
        assertThat(row.getPipelineKind()).isNull();
    }

    private void classifyAs(com.financeos.gmail.classify.EmailKind kind, com.financeos.gmail.classify.ClassifierConfidence confidence) {
        EmailClassifier fixed = mock(EmailClassifier.class);
        when(fixed.classify(any())).thenReturn(new ClassificationResult(kind,
                com.financeos.gmail.classify.EmailSubtype.OTP, confidence, "hdfc", List.of("test"), EmailClassifier.VERSION));
        ReflectionTestUtils.setField(service, "emailClassifier", fixed);
    }

    @Test
    void gate_otherAtHighConfidenceSkipsTheAiRead() {
        classifyAs(com.financeos.gmail.classify.EmailKind.OTHER, com.financeos.gmail.classify.ClassifierConfidence.HIGH);
        GmailMessage m = new GmailMessage("g1", Instant.now(), "alerts@hdfcbank.net", "Offers", "s", "Unsubscribe here.", null, List.of());

        GmailProcessedMessage row = run(m);

        assertThat(row.getStatus()).isEqualTo(GmailProcessedStatus.SKIPPED_NOT_TRANSACTION);
        assertThat(row.getError()).isNull();
        assertThat(row.getPipelineKind()).isEqualTo("OTHER");
        verifyNoInteractions(geminiExtractor);
        verifyNoInteractions(statementReconciliationService);
    }

    @Test
    void gate_otherAtHighConfidenceWithAnAttachmentAlsoSkipsTheStatementPath() {
        classifyAs(com.financeos.gmail.classify.EmailKind.OTHER, com.financeos.gmail.classify.ClassifierConfidence.HIGH);
        GmailMessage m = new GmailMessage("g2", Instant.now(), "alerts@hdfcbank.net", "Invoice", "s", "attached", null,
                List.of(new GmailAttachment("att", "invoice.pdf", "application/pdf", new byte[]{1})));

        GmailProcessedMessage row = run(m);

        assertThat(row.getStatus()).isEqualTo(GmailProcessedStatus.SKIPPED_NOT_TRANSACTION);
        verifyNoInteractions(geminiExtractor);
        verifyNoInteractions(statementReconciliationService);
    }

    @Test
    void gate_otherBelowHighStillGetsTheAiRead() {
        for (var confidence : new com.financeos.gmail.classify.ClassifierConfidence[] {
                com.financeos.gmail.classify.ClassifierConfidence.MEDIUM, com.financeos.gmail.classify.ClassifierConfidence.LOW}) {
            reset(geminiExtractor);
            classifyAs(com.financeos.gmail.classify.EmailKind.OTHER, confidence);
            GmailMessage m = new GmailMessage("g3" + confidence, Instant.now(), "alerts@hdfcbank.net", "Hi", "s", "hi", null, List.of());
            when(geminiExtractor.extract(m)).thenReturn(GeminiExtractionResult.notTransaction());

            run(m);

            verify(geminiExtractor).extract(m);
        }
    }

    @Test
    void gate_alertAtHighConfidenceStillGetsTheAiRead() {
        classifyAs(com.financeos.gmail.classify.EmailKind.ALERT, com.financeos.gmail.classify.ClassifierConfidence.HIGH);
        GmailMessage m = alert("g4", "alerts@hdfcbank.net");
        when(geminiExtractor.extract(m)).thenReturn(GeminiExtractionResult.notTransaction());

        run(m);

        verify(geminiExtractor).extract(m);
    }

    @Test
    void gate_statementAtHighConfidenceStillTakesTheStatementPath() {
        classifyAs(com.financeos.gmail.classify.EmailKind.STATEMENT, com.financeos.gmail.classify.ClassifierConfidence.HIGH);
        GmailMessage m = new GmailMessage("g5", Instant.now(), "alerts@hdfcbank.net", "Statement", "s", "attached", null,
                List.of(new GmailAttachment("att", "stmt.pdf", "application/pdf", new byte[]{1})));
        when(statementReconciliationService.reconcile(eq(connection), eq(m))).thenReturn(new ReconSummary(0, 0, 0));

        run(m);

        verify(statementReconciliationService).reconcile(connection, m);
    }

    private void classifyAttachment(com.financeos.gmail.classify.EmailKind kind, com.financeos.gmail.classify.EmailSubtype subtype) {
        EmailClassifier fixed = mock(EmailClassifier.class);
        when(fixed.classify(any())).thenReturn(new ClassificationResult(kind, subtype,
                com.financeos.gmail.classify.ClassifierConfidence.MEDIUM, "axis", List.of("test"), EmailClassifier.VERSION));
        ReflectionTestUtils.setField(service, "emailClassifier", fixed);
    }

    private static GmailMessage pdfMail(String id, String subject, String file) {
        return new GmailMessage(id, Instant.now(), "alerts@hdfcbank.net", subject, "s", "attached", null,
                List.of(new GmailAttachment("att", file, "application/pdf", new byte[]{1})));
    }

    @Test
    void letterPdfClassifiedOtherDocumentNeverReconciles() {
        classifyAttachment(com.financeos.gmail.classify.EmailKind.OTHER, com.financeos.gmail.classify.EmailSubtype.DOCUMENT);
        GmailMessage m = pdfMail("L1", "Important Communication : Cancellation of your Axis Bank Credit Card", "ACCOUNT CANCELLATION.pdf");

        GmailProcessedMessage row = run(m);

        assertThat(row.getStatus()).isEqualTo(GmailProcessedStatus.SKIPPED_NOT_TRANSACTION);
        assertThat(row.getPipelineKind()).isEqualTo("OTHER");
        assertThat(row.getError()).isEqualTo("Not a statement: a bank letter or document");
        verifyNoInteractions(statementReconciliationService);
    }

    @Test
    void cardStatementPdfClassifiedStatementReconciles() {
        classifyAttachment(com.financeos.gmail.classify.EmailKind.STATEMENT, com.financeos.gmail.classify.EmailSubtype.CARD_STATEMENT);
        GmailMessage m = pdfMail("L2", "Credit Card Statement", "stmt.pdf");
        when(statementReconciliationService.reconcile(eq(connection), eq(m))).thenReturn(new ReconSummary(0, 0, 0));

        GmailProcessedMessage row = run(m);

        verify(statementReconciliationService).reconcile(connection, m);
        assertThat(row.getPipelineKind()).isEqualTo("STATEMENT");
    }

    @Test
    void unknownOrFailedClassifierAnswerStillReconciles() {
        classifyAttachment(com.financeos.gmail.classify.EmailKind.OTHER, com.financeos.gmail.classify.EmailSubtype.UNKNOWN);
        GmailMessage m = pdfMail("L4", "HDFC Bank - October", "1234.pdf");
        when(statementReconciliationService.reconcile(eq(connection), eq(m))).thenReturn(new ReconSummary(0, 0, 0));

        run(m);

        verify(statementReconciliationService).reconcile(connection, m);
    }

    @Test
    void noClassifierResultStillReconciles() {
        ReflectionTestUtils.setField(service, "emailClassifier", null);
        GmailMessage m = pdfMail("L3", "Cancellation letter", "letter.pdf");
        when(statementReconciliationService.reconcile(eq(connection), eq(m))).thenReturn(new ReconSummary(0, 0, 0));

        run(m);

        verify(statementReconciliationService).reconcile(connection, m);
    }

    private GmailProcessedMessage transferAlert(String id, String geminiLast4) {
        GmailMessage m = new GmailMessage(id, Instant.now(), "alerts@hdfcbank.net", "Transaction alert", "s",
                "INR 500 debited from your A/c XX1234 to A/c XX8180", null, List.of());
        when(geminiExtractor.extract(m)).thenReturn(GeminiExtractionResult.success(
                new com.financeos.gmail.ingest.gemini.ExtractedTransaction(true, new java.math.BigDecimal("500"), "INR", "DEBIT",
                        java.time.LocalDate.now().toString(), "Someone", geminiLast4, 0.9), java.time.LocalDate.now()));
        return run(m);
    }

    private static AccountResolver.ResolvedCard ownedAccount() {
        var account = new com.financeos.domain.account.Account();
        account.setId(UUID.randomUUID());
        return new AccountResolver.ResolvedCard(account, null);
    }

    @Test
    void payeeLast4FallsBackToTheOnlyOwnedMaskedNumber() {
        var owned = ownedAccount();
        when(accountResolver.resolve(any(), any(), any())).thenReturn(Optional.empty());
        when(accountResolver.resolve(eq("1234"), any(), any())).thenReturn(Optional.of(owned));

        GmailProcessedMessage row = transferAlert("F1", "8180");

        assertThat(row.getStatus()).isNotEqualTo(GmailProcessedStatus.UNRESOLVED_ACCOUNT);
        assertThat(row.getAccount()).isSameAs(owned.account());
        assertThat(row.getExtractedLast4()).isEqualTo("1234");
    }

    @Test
    void twoResolvableCandidatesStayUnresolved() {
        when(accountResolver.resolve(any(), any(), any())).thenReturn(Optional.empty());
        when(accountResolver.resolve(eq("1234"), any(), any())).thenReturn(Optional.of(ownedAccount()));
        when(accountResolver.resolve(eq("8180"), any(), any())).thenReturn(Optional.of(ownedAccount()));

        GmailProcessedMessage row = transferAlert("F2", "9999");

        assertThat(row.getStatus()).isEqualTo(GmailProcessedStatus.UNRESOLVED_ACCOUNT);
        assertThat(row.getAccount()).isNull();
        assertThat(row.getExtractedLast4()).isEqualTo("9999");
    }

    @Test
    void noOwnedCandidateStaysUnresolved() {
        when(accountResolver.resolve(any(), any(), any())).thenReturn(Optional.empty());

        GmailProcessedMessage row = transferAlert("F3", "8180");

        assertThat(row.getStatus()).isEqualTo(GmailProcessedStatus.UNRESOLVED_ACCOUNT);
        assertThat(row.getExtractedLast4()).isEqualTo("8180");
    }

    private void classifyWith(com.financeos.gmail.classify.EmailKind kind, com.financeos.gmail.classify.EmailSubtype subtype,
                              com.financeos.gmail.classify.ClassifierConfidence confidence, String... signals) {
        EmailClassifier fixed = mock(EmailClassifier.class);
        when(fixed.classify(any())).thenReturn(new ClassificationResult(kind, subtype, confidence, "axis",
                List.of(signals), EmailClassifier.VERSION));
        ReflectionTestUtils.setField(service, "emailClassifier", fixed);
    }

    @Test
    void gate_declinedAtMediumSkipsTheAiRead() {
        classifyWith(com.financeos.gmail.classify.EmailKind.OTHER, com.financeos.gmail.classify.EmailSubtype.DECLINED,
                com.financeos.gmail.classify.ClassifierConfidence.MEDIUM, "bank:axis", "amt", "decline");

        GmailProcessedMessage row = run(alert("d1", "alerts@hdfcbank.net"));

        assertThat(row.getStatus()).isEqualTo(GmailProcessedStatus.SKIPPED_NOT_TRANSACTION);
        assertThat(row.getPipelineKind()).isEqualTo("OTHER");
        verifyNoInteractions(geminiExtractor);
    }

    @Test
    void gate_mandateServiceSkipsTheAiRead() {
        classifyWith(com.financeos.gmail.classify.EmailKind.OTHER, com.financeos.gmail.classify.EmailSubtype.SERVICE,
                com.financeos.gmail.classify.ClassifierConfidence.MEDIUM, "bank:axis", "kw:mandate");

        GmailProcessedMessage row = run(alert("d2", "alerts@hdfcbank.net"));

        assertThat(row.getStatus()).isEqualTo(GmailProcessedStatus.SKIPPED_NOT_TRANSACTION);
        assertThat(row.getPipelineKind()).isEqualTo("OTHER");
        verifyNoInteractions(geminiExtractor);
    }

    @Test
    void gate_mandateActivationWithInitialDebitStillGetsTheAiRead() {
        GmailMessage m = new GmailMessage("d5", Instant.now(), "Axis Bank <alerts@hdfcbank.net>",
                "Auto Pay facility has been activated", "s",
                "Auto Pay facility has been successfully activated on your Axis Bank Card no. XX9894 for INR 130.00 from Google Play "
                        + "and initial amt of INR 35.00 has been debited. Google Play can initiate subsequent transactions "
                        + "for a maximum amount of INR 130.00.", null, List.of());
        when(geminiExtractor.extract(m)).thenReturn(GeminiExtractionResult.notTransaction());

        run(m);

        verify(geminiExtractor).extract(m);
    }

    @Test
    void gate_debitAlertAtMediumStillGetsTheAiRead() {
        classifyWith(com.financeos.gmail.classify.EmailKind.ALERT, com.financeos.gmail.classify.EmailSubtype.DEBIT,
                com.financeos.gmail.classify.ClassifierConfidence.MEDIUM, "bank:axis", "amt");
        GmailMessage m = alert("d3", "alerts@hdfcbank.net");
        when(geminiExtractor.extract(m)).thenReturn(GeminiExtractionResult.notTransaction());

        run(m);

        verify(geminiExtractor).extract(m);
    }

    @Test
    void gate_declinedOrMandateWithPdfAttachmentStillTakesTheStatementPath() {
        classifyWith(com.financeos.gmail.classify.EmailKind.OTHER, com.financeos.gmail.classify.EmailSubtype.DECLINED,
                com.financeos.gmail.classify.ClassifierConfidence.MEDIUM, "kw:mandate", "decline");
        GmailMessage m = pdfMail("d4", "HDFC Bank - October", "1234.pdf");
        when(statementReconciliationService.reconcile(eq(connection), eq(m))).thenReturn(new ReconSummary(0, 0, 0));

        run(m);

        verify(statementReconciliationService).reconcile(connection, m);
        verifyNoInteractions(geminiExtractor);
    }
}
