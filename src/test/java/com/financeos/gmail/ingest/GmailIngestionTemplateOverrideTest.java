package com.financeos.gmail.ingest;

import com.financeos.core.observability.ObservabilityMetrics;
import com.financeos.domain.categorization.CategorizationService;
import com.financeos.domain.transaction.TransactionRepository;
import com.financeos.domain.user.User;
import com.financeos.domain.account.Account;
import com.financeos.domain.transaction.Transaction;
import com.financeos.gmail.extract.Direction;
import com.financeos.gmail.extract.ExtractedTransaction;
import com.financeos.gmail.extract.Tier;
import com.financeos.gmail.extract.TransactionExtractor;
import com.financeos.gmail.domain.*;
import com.financeos.gmail.classify.*;
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

/** Owner rule 06/11/2026: a complete HIGH ALERT template read wins over an AI "not a transaction". */
class GmailIngestionTemplateOverrideTest {

    private static final String ALERT_BODY = "Rs. 725.00 has been debited from your HDFC Bank Credit Card ending 4821 towards BLUE LEAF CAFE.";

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
    private EmailClassifier classifier;

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

        service = new GmailIngestionService(gmailEngine, cursorService, senderRepository, accountResolver,
                writer, geminiExtractor, processedMessageRepository, backfillDemandRepository,
                ingestProperties, statementReconciliationService, mock(CategorizationService.class), transactionRepository,
                transactionManager, mock(ParkedMessageSettler.class));
        engine = mock(TransactionExtractor.class);
        classifier = mock(EmailClassifier.class);
        ReflectionTestUtils.setField(service, "emailClassifier", classifier);
        registry = new SimpleMeterRegistry();
        ReflectionTestUtils.setField(service, "transactionExtractor", engine);
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

    private static final LocalDate DAY = LocalDate.of(2026, 10, 20);

    private static GmailMessage msg() {
        return new GmailMessage("m1", Instant.parse("2026-10-20T10:00:00Z"), "HDFC <alerts@hdfcbank.net>", "Alert", "s", "body", null, List.of());
    }

    private static ExtractedTransaction eng(Direction dir, String currency) {
        return new ExtractedTransaction(new BigDecimal("64.00"), currency, dir, DAY, "chennaimetrorail", "4821", Tier.TEMPLATE, "hdfc.upi.debit.v1", 0.95);
    }

    private Account account() {
        Account a = new Account();
        a.setId(UUID.randomUUID());
        a.setName("HDFC");
        a.setIngestFromDate(LocalDate.of(2026, 1, 1));
        return a;
    }

    private GmailProcessedMessage drop(ExtractedTransaction e, ClassifierConfidence conf, EmailSubtype subtype, boolean resolves) {
        GmailMessage m = msg();
        when(classifier.classify(m)).thenReturn(new ClassificationResult(subtype.kind(), subtype, conf, "hdfc", List.of(), 1));
        when(engine.extract(m)).thenReturn(Optional.of(e));
        when(geminiExtractor.extract(m)).thenReturn(GeminiExtractionResult.notTransaction().withProvider("gemini/x"));
        Account acct = account();
        when(accountResolver.resolve(any(), any(), any())).thenReturn(resolves
                ? Optional.of(new AccountResolver.ResolvedCard(acct, null)) : Optional.empty());
        GmailProcessedMessage written = new GmailProcessedMessage();
        written.setStatus(GmailProcessedStatus.CREATED);
        written.setTransaction(new Transaction());
        when(writer.writeTransaction(any(), any(), any(), any(), any(), any())).thenReturn(written);
        return run(m);
    }

    private double overrides() {
        var c = registry.find("financeos.gmail.alert.template_override").counter();
        return c == null ? 0 : c.count();
    }

    @Test
    void highAlertWithCompleteTemplateIsWritten() {
        GmailProcessedMessage row = drop(eng(Direction.DEBIT, "INR"), ClassifierConfidence.HIGH, EmailSubtype.DEBIT, true);

        var captor = org.mockito.ArgumentCaptor.forClass(GeminiExtractionResult.class);
        verify(writer).writeTransaction(any(), eq("m1"), captor.capture(), any(), any(), any());
        assertThat(captor.getValue().direction()).isEqualTo("DEBIT");
        assertThat(captor.getValue().amount()).isEqualByComparingTo("64.00");
        assertThat(captor.getValue().date()).isEqualTo(DAY);
        assertThat(row.getStatus()).isEqualTo(GmailProcessedStatus.CREATED);
        assertThat(row.getExtractMismatch()).isEqualTo(GmailIngestionService.TEMPLATE_OVERRIDE_NOTE);
        assertThat(overrides()).isEqualTo(1);
    }

    @Test
    void foreignCurrencyTemplateIsWritten() {
        drop(eng(Direction.DEBIT, "USD"), ClassifierConfidence.HIGH, EmailSubtype.DEBIT, true);
        verify(writer).writeTransaction(any(), any(), any(), any(), any(), any());
    }

    @Test
    void mediumConfidenceStaysSkipped() {
        GmailProcessedMessage row = drop(eng(Direction.DEBIT, "INR"), ClassifierConfidence.MEDIUM, EmailSubtype.DEBIT, true);
        assertThat(row.getStatus()).isEqualTo(GmailProcessedStatus.SKIPPED_NOT_TRANSACTION);
        verify(writer, never()).writeTransaction(any(), any(), any(), any(), any(), any());
        assertThat(overrides()).isZero();
    }

    @Test
    void declinedSubtypeStaysSkipped() {
        GmailProcessedMessage row = drop(eng(Direction.DEBIT, "INR"), ClassifierConfidence.HIGH, EmailSubtype.DECLINED, true);
        assertThat(row.getStatus()).isEqualTo(GmailProcessedStatus.SKIPPED_NOT_TRANSACTION);
        verify(writer, never()).writeTransaction(any(), any(), any(), any(), any(), any());
    }

    @Test
    void missingDirectionStaysSkipped() {
        GmailProcessedMessage row = drop(eng(null, "INR"), ClassifierConfidence.HIGH, EmailSubtype.DEBIT, true);
        assertThat(row.getStatus()).isEqualTo(GmailProcessedStatus.SKIPPED_NOT_TRANSACTION);
        verify(writer, never()).writeTransaction(any(), any(), any(), any(), any(), any());
    }

    @Test
    void missingCurrencyStaysSkipped() {
        GmailProcessedMessage row = drop(eng(Direction.DEBIT, null), ClassifierConfidence.HIGH, EmailSubtype.DEBIT, true);
        assertThat(row.getStatus()).isEqualTo(GmailProcessedStatus.SKIPPED_NOT_TRANSACTION);
    }

    @Test
    void unresolvedAccountStaysSkipped() {
        GmailProcessedMessage row = drop(eng(Direction.DEBIT, "INR"), ClassifierConfidence.HIGH, EmailSubtype.DEBIT, false);
        assertThat(row.getStatus()).isEqualTo(GmailProcessedStatus.SKIPPED_NOT_TRANSACTION);
        verify(writer, never()).writeTransaction(any(), any(), any(), any(), any(), any());
    }
}
