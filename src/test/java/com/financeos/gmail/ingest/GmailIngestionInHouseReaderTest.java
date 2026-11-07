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

/** INGEST_READER=INHOUSE: the classifier and the engine read Gmail alert mails, the AI is never called. */
class GmailIngestionInHouseReaderTest {

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
    private GmailIngestProperties ingestProperties;

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
        ingestProperties = mock(GmailIngestProperties.class);
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
        when(ingestProperties.getReader()).thenReturn(GmailIngestProperties.Reader.INHOUSE);
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

    private static GmailMessage msg(Instant sent) {
        return new GmailMessage("m1", sent, "HDFC <alerts@hdfcbank.net>", "Alert", "s", "body", null, List.of());
    }

    private static GmailMessage msg() {
        return msg(Instant.parse("2026-10-20T10:00:00Z"));
    }

    private static ExtractedTransaction eng(BigDecimal amount, String currency, Direction dir, LocalDate date, String last4) {
        return new ExtractedTransaction(amount, currency, dir, date, "chennaimetrorail", last4, Tier.TEMPLATE, "hdfc.upi.debit.v1", 0.95);
    }

    private static ExtractedTransaction full() {
        return eng(new BigDecimal("64.00"), "INR", Direction.DEBIT, DAY, "4821");
    }

    private Account account() {
        Account a = new Account();
        a.setId(UUID.randomUUID());
        a.setName("HDFC");
        a.setIngestFromDate(LocalDate.of(2026, 1, 1));
        return a;
    }

    private GmailProcessedMessage drop(GmailMessage m, EmailSubtype subtype, ClassifierConfidence conf,
                                       Optional<ExtractedTransaction> read, boolean resolves) {
        when(classifier.classify(m)).thenReturn(new ClassificationResult(subtype.kind(), subtype, conf, "hdfc", List.of(), 1));
        when(engine.extract(m)).thenReturn(read);
        when(geminiExtractor.extract(m)).thenReturn(GeminiExtractionResult.notTransaction().withProvider("gemini/x"));
        when(accountResolver.resolve(any(), any(), any())).thenReturn(resolves
                ? Optional.of(new AccountResolver.ResolvedCard(account(), null)) : Optional.empty());
        GmailProcessedMessage written = new GmailProcessedMessage();
        written.setStatus(GmailProcessedStatus.CREATED);
        written.setTransaction(new Transaction());
        when(writer.writeTransaction(any(), any(), any(), any(), any(), any())).thenReturn(written);
        return run(m);
    }

    private double reader(String reader, String outcome) {
        var c = registry.find("financeos.gmail.reader").tag("reader", reader).tag("outcome", outcome).counter();
        return c == null ? 0 : c.count();
    }

    @Test
    void templateFullReadIsWrittenWithoutTheAi() {
        GmailProcessedMessage row = drop(msg(), EmailSubtype.DEBIT, ClassifierConfidence.MEDIUM, Optional.of(full()), true);

        var captor = org.mockito.ArgumentCaptor.forClass(GeminiExtractionResult.class);
        verify(writer).writeTransaction(any(), eq("m1"), captor.capture(), any(), any(), any());
        verify(geminiExtractor, never()).extract(any());
        assertThat(captor.getValue().direction()).isEqualTo("DEBIT");
        assertThat(captor.getValue().amount()).isEqualByComparingTo("64.00");
        assertThat(captor.getValue().date()).isEqualTo(DAY);
        assertThat(captor.getValue().accountLast4()).isEqualTo("4821");
        assertThat(row.getStatus()).isEqualTo(GmailProcessedStatus.CREATED);
        assertThat(row.getPipelineKind()).isEqualTo("ALERT");
        assertThat(row.getExtractTier()).isEqualTo("TEMPLATE");
        assertThat(row.getExtractTemplate()).isEqualTo("hdfc.upi.debit.v1");
        assertThat(row.getExtractAiProvider()).isNull();
        assertThat(reader("INHOUSE", "written")).isEqualTo(1);
    }

    @Test
    void missingDateUsesTheSentDateInIst() {
        // 20:00 UTC on the 20th is 01:30 IST on the 21st.
        drop(msg(Instant.parse("2026-10-20T20:00:00Z")), EmailSubtype.CREDIT, ClassifierConfidence.MEDIUM,
                Optional.of(eng(new BigDecimal("5373.86"), "INR", Direction.CREDIT, null, "4821")), true);

        var captor = org.mockito.ArgumentCaptor.forClass(GeminiExtractionResult.class);
        verify(writer).writeTransaction(any(), any(), captor.capture(), any(), any(), any());
        assertThat(captor.getValue().date()).isEqualTo(LocalDate.of(2026, 10, 21));
        verify(geminiExtractor, never()).extract(any());
    }

    @Test
    void readDateAfterTheSentDateIsCappedToIt() {
        drop(msg(), EmailSubtype.DEBIT, ClassifierConfidence.MEDIUM,
                Optional.of(eng(new BigDecimal("64.00"), "INR", Direction.DEBIT, LocalDate.of(2026, 10, 25), "4821")), true);

        var captor = org.mockito.ArgumentCaptor.forClass(GeminiExtractionResult.class);
        verify(writer).writeTransaction(any(), any(), captor.capture(), any(), any(), any());
        assertThat(captor.getValue().date()).isEqualTo(DAY);
    }

    @Test
    void nothingReadIsParkedAsCouldntRead() {
        GmailProcessedMessage row = drop(msg(), EmailSubtype.DEBIT, ClassifierConfidence.MEDIUM, Optional.empty(), true);

        assertThat(row.getStatus()).isEqualTo(GmailProcessedStatus.FAILED_PERMANENT);
        assertThat(row.getError()).isEqualTo("Couldn't read this email (in-house reader)");
        verify(geminiExtractor, never()).extract(any());
        verify(writer, never()).writeTransaction(any(), any(), any(), any(), any(), any());
        assertThat(reader("INHOUSE", "couldnt_read")).isEqualTo(1);
    }

    @Test
    void incompleteReadsAreParkedAsCouldntRead() {
        for (ExtractedTransaction e : List.of(
                eng(null, "INR", Direction.DEBIT, DAY, "4821"),
                eng(new BigDecimal("64.00"), "INR", null, DAY, "4821"),
                eng(new BigDecimal("64.00"), null, Direction.DEBIT, DAY, "4821"))) {
            setUp();
            GmailProcessedMessage row = drop(msg(), EmailSubtype.DEBIT, ClassifierConfidence.MEDIUM, Optional.of(e), true);
            assertThat(row.getStatus()).isEqualTo(GmailProcessedStatus.FAILED_PERMANENT);
            verify(geminiExtractor, never()).extract(any());
            verify(writer, never()).writeTransaction(any(), any(), any(), any(), any(), any());
        }
    }

    @Test
    void classifierOtherIsSkippedWithoutTheAi() {
        GmailProcessedMessage row = drop(msg(), EmailSubtype.SERVICE, ClassifierConfidence.MEDIUM, Optional.of(full()), true);

        assertThat(row.getStatus()).isEqualTo(GmailProcessedStatus.SKIPPED_NOT_TRANSACTION);
        assertThat(row.getError()).isEqualTo("Not a transaction alert (in-house classifier)");
        assertThat(row.getPipelineKind()).isEqualTo("OTHER");
        verify(geminiExtractor, never()).extract(any());
        verify(writer, never()).writeTransaction(any(), any(), any(), any(), any(), any());
        assertThat(reader("INHOUSE", "not_alert")).isEqualTo(1);
    }

    @Test
    void readWithoutLast4StaysUnresolvedEvenWhenTheBankHasAnAccount() {
        // The resolver answers nothing for a null last4 (never guesses); the mail stays for the user to place.
        GmailProcessedMessage row = drop(msg(), EmailSubtype.CREDIT, ClassifierConfidence.MEDIUM,
                Optional.of(eng(new BigDecimal("5373.86"), "INR", Direction.CREDIT, null, null)), false);

        assertThat(row.getStatus()).isEqualTo(GmailProcessedStatus.UNRESOLVED_ACCOUNT);
        verify(writer, never()).writeTransaction(any(), any(), any(), any(), any(), any());
        verify(geminiExtractor, never()).extract(any());
        assertThat(reader("INHOUSE", "unresolved")).isEqualTo(1);
    }

    @Test
    void aiModeStillCallsTheAiExactlyOnce() {
        when(ingestProperties.getReader()).thenReturn(GmailIngestProperties.Reader.AI);
        GmailMessage m = msg();
        when(classifier.classify(m)).thenReturn(new ClassificationResult(EmailKind.ALERT, EmailSubtype.DEBIT,
                ClassifierConfidence.MEDIUM, "hdfc", List.of(), 1));
        when(engine.extract(m)).thenReturn(Optional.empty());
        when(geminiExtractor.extract(m)).thenReturn(new GeminiExtractionResult(true, new BigDecimal("64.00"), "INR", "DEBIT", DAY,
                "cafe", "4821", 0.9, true, null).withProvider("gemini/x"));
        when(accountResolver.resolve(any(), any(), any())).thenReturn(Optional.of(new AccountResolver.ResolvedCard(account(), null)));
        GmailProcessedMessage written = new GmailProcessedMessage();
        written.setStatus(GmailProcessedStatus.CREATED);
        written.setTransaction(new Transaction());
        when(writer.writeTransaction(any(), any(), any(), any(), any(), any())).thenReturn(written);

        GmailProcessedMessage row = run(m);

        verify(geminiExtractor, times(1)).extract(m);
        verify(writer).writeTransaction(any(), any(), any(), any(), any(), any());
        assertThat(row.getStatus()).isEqualTo(GmailProcessedStatus.CREATED);
        assertThat(reader("AI", "written")).isEqualTo(1);
    }

    @Test
    void noDateAndNoSentDateIsParkedAsCouldntRead() {
        GmailMessage m = new GmailMessage("m1", null, "HDFC <alerts@hdfcbank.net>", "Alert", "s", "body", null, List.of());
        GmailProcessedMessage row = drop(m, EmailSubtype.DEBIT, ClassifierConfidence.MEDIUM,
                Optional.of(eng(new BigDecimal("64.00"), "INR", Direction.DEBIT, null, "4821")), true);

        assertThat(row.getStatus()).isEqualTo(GmailProcessedStatus.FAILED_PERMANENT);
        assertThat(row.getError()).isEqualTo("Couldn't read this email (in-house reader)");
        verify(writer, never()).writeTransaction(any(), any(), any(), any(), any(), any());
        verify(geminiExtractor, never()).extract(any());
    }

    @Test
    void noLast4AndNoMaskedNumberInTheMailStaysUnresolved() {
        // The resolver only knows card 4821 (like a user with one account at the bank): null and unknown tails resolve to nothing.
        GmailMessage m = msg();
        when(classifier.classify(m)).thenReturn(new ClassificationResult(EmailKind.ALERT, EmailSubtype.CREDIT,
                ClassifierConfidence.MEDIUM, "hdfc", List.of(), 1));
        when(engine.extract(m)).thenReturn(Optional.of(eng(new BigDecimal("5373.86"), "INR", Direction.CREDIT, null, null)));
        when(accountResolver.resolve(any(), any(), any())).thenAnswer(i ->
                "4821".equals(i.getArgument(0)) ? Optional.of(new AccountResolver.ResolvedCard(account(), null)) : Optional.empty());

        GmailProcessedMessage row = run(m);

        assertThat(row.getStatus()).isEqualTo(GmailProcessedStatus.UNRESOLVED_ACCOUNT);
        assertThat(row.getError()).isEqualTo("No card or account number in this email");
        verify(writer, never()).writeTransaction(any(), any(), any(), any(), any(), any());
    }

    @Test
    void noLast4ButOneMaskedNumberInTheMailResolvesThroughTheHintsFallback() {
        GmailMessage m = new GmailMessage("m1", Instant.parse("2026-10-20T10:00:00Z"), "HDFC <alerts@hdfcbank.net>", "Alert", "s",
                "Rs. 64.00 credited to your account XX4821 on 20-10-2026.", null, List.of());
        when(classifier.classify(m)).thenReturn(new ClassificationResult(EmailKind.ALERT, EmailSubtype.CREDIT,
                ClassifierConfidence.MEDIUM, "hdfc", List.of(), 1));
        when(engine.extract(m)).thenReturn(Optional.of(eng(new BigDecimal("64.00"), "INR", Direction.CREDIT, DAY, null)));
        when(accountResolver.resolve(any(), any(), any())).thenAnswer(i ->
                "4821".equals(i.getArgument(0)) ? Optional.of(new AccountResolver.ResolvedCard(account(), null)) : Optional.empty());
        GmailProcessedMessage written = new GmailProcessedMessage();
        written.setStatus(GmailProcessedStatus.CREATED);
        written.setTransaction(new Transaction());
        when(writer.writeTransaction(any(), any(), any(), any(), any(), any())).thenReturn(written);

        GmailProcessedMessage row = run(m);

        assertThat(row.getStatus()).isEqualTo(GmailProcessedStatus.CREATED);
        assertThat(row.getExtractedLast4()).isEqualTo("4821");
        verify(geminiExtractor, never()).extract(any());
    }

    @Test
    void nullClassifierKindContinuesToTheEngineReadAndWrites() {
        GmailMessage m = msg();
        when(classifier.classify(m)).thenReturn(null);
        when(engine.extract(m)).thenReturn(Optional.of(full()));
        when(accountResolver.resolve(any(), any(), any())).thenReturn(Optional.of(new AccountResolver.ResolvedCard(account(), null)));
        GmailProcessedMessage written = new GmailProcessedMessage();
        written.setStatus(GmailProcessedStatus.CREATED);
        written.setTransaction(new Transaction());
        when(writer.writeTransaction(any(), any(), any(), any(), any(), any())).thenReturn(written);

        GmailProcessedMessage row = run(m);

        assertThat(row.getClassifiedKind()).isNull();
        assertThat(row.getStatus()).isEqualTo(GmailProcessedStatus.CREATED);
        verify(writer).writeTransaction(any(), any(), any(), any(), any(), any());
        verify(geminiExtractor, never()).extract(any());
    }
}
