package com.financeos.gmail.ingest;

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

/** Shadow offline-extraction wiring in {@link GmailIngestionService}: fields, mismatch, metrics, merge check; outcome unchanged. */
class GmailIngestionExtractShadowTest {

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

    private static GmailMessage msg(String id, String from) {
        return new GmailMessage(id, Instant.now(), "Sender <" + from + ">", "Alert", "s", "body", null, List.of());
    }

    private static ExtractedTransaction eng(String amount, String currency, Direction dir, LocalDate date, String cp, String last4) {
        return new ExtractedTransaction(new BigDecimal(amount), currency, dir, date, cp, last4, Tier.TEMPLATE, "hdfc-card", 0.95);
    }

    private static GeminiExtractionResult ai(String amount, String currency, String dir, LocalDate date, String desc, String last4) {
        return new GeminiExtractionResult(true, new BigDecimal(amount), currency, dir, date, desc, last4, 0.9, true, null)
                .withProvider("gemini/gemini-2.5-flash");
    }

    private static GeminiExtractionResult aiNone() {
        return GeminiExtractionResult.notTransaction().withProvider("gemini/gemini-2.5-flash");
    }

    private static ExtractedTransaction base() {
        return eng("725.00", "INR", Direction.DEBIT, DAY, "BLUE LEAF CAFE", "4821");
    }

    private static GeminiExtractionResult aiBase() {
        return ai("725.00", "INR", "DEBIT", DAY, "Blue Leaf Cafe", "4821");
    }

    private GmailProcessedMessage runWith(GmailMessage m, Optional<ExtractedTransaction> e, GeminiExtractionResult a) {
        when(engine.extract(m)).thenReturn(e);
        when(geminiExtractor.extract(m)).thenReturn(a);
        when(accountResolver.resolve(any(), any())).thenReturn(Optional.empty());
        return run(m);
    }

    private double compare(String tier, String result, String provider) {
        var c = registry.find("financeos.gmail.extract.compare").tag("tier", tier).tag("result", result).tag("provider", provider).counter();
        return c == null ? 0 : c.count();
    }

    private double fieldMismatch(String field) {
        var c = registry.find("financeos.gmail.extract.field_mismatch").tag("field", field).counter();
        return c == null ? 0 : c.count();
    }

    private double merge(String kind) {
        var c = registry.find("financeos.gmail.extract.merge").tag("kind", kind).counter();
        return c == null ? 0 : c.count();
    }

    @Test
    void agreementStoresEngineFieldsAndCountsMatch() {
        GmailProcessedMessage row = runWith(msg("a1", "alerts@hdfcbank.net"), Optional.of(base()), aiBase());

        assertThat(row.getStatus()).isEqualTo(GmailProcessedStatus.UNRESOLVED_ACCOUNT);
        assertThat(row.getExtractTier()).isEqualTo("TEMPLATE");
        assertThat(row.getExtractTemplate()).isEqualTo("hdfc-card");
        assertThat(row.getExtractVersion()).isEqualTo(TransactionExtractor.VERSION);
        assertThat(row.getExtractAmount()).isEqualByComparingTo("725.00");
        assertThat(row.getExtractCurrency()).isEqualTo("INR");
        assertThat(row.getExtractDirection()).isEqualTo("DEBIT");
        assertThat(row.getExtractDate()).isEqualTo(DAY);
        assertThat(row.getExtractLast4()).isEqualTo("4821");
        assertThat(row.getExtractCounterparty()).isEqualTo("BLUE LEAF CAFE");
        assertThat(row.getExtractMismatch()).isNull();
        assertThat(row.getExtractAiProvider()).isEqualTo("gemini/gemini-2.5-flash");
        assertThat(compare("TEMPLATE", "match", "gemini")).isEqualTo(1.0);
        assertThat(registry.find("financeos.gmail.extract.field_mismatch").counter()).isNull();
    }

    @Test
    void eachFieldMismatchIsListedAndCounted() {
        GeminiExtractionResult a = ai("726.00", "USD", "CREDIT", DAY.plusDays(1), "Other Shop", "1111");
        GmailProcessedMessage row = runWith(msg("m1", "alerts@hdfcbank.net"), Optional.of(base()), a);

        assertThat(row.getExtractMismatch()).isEqualTo("amount,currency,direction,date,last4,counterparty");
        assertThat(compare("TEMPLATE", "mismatch", "gemini")).isEqualTo(1.0);
        for (String f : List.of("amount", "currency", "direction", "date", "last4", "counterparty")) {
            assertThat(fieldMismatch(f)).as(f).isEqualTo(1.0);
        }
    }

    @Test
    void singleFieldMismatchOnly() {
        GmailProcessedMessage row = runWith(msg("m2", "alerts@hdfcbank.net"), Optional.of(base()),
                ai("725.00", "inr", "debit", DAY, "Blue Leaf Cafe Pune", "XX4821"));
        assertThat(row.getExtractMismatch()).isNull();

        GmailProcessedMessage row2 = runWith(msg("m3", "alerts@hdfcbank.net"), Optional.of(base()),
                ai("725.00", "INR", "DEBIT", DAY.minusDays(1), "Blue Leaf Cafe", "4821"));
        assertThat(row2.getExtractMismatch()).isEqualTo("date");
        assertThat(fieldMismatch("date")).isEqualTo(1.0);
        assertThat(fieldMismatch("amount")).isZero();
    }

    @Test
    void engineOnlyWhenAiSaysNotATransaction() {
        GmailProcessedMessage row = runWith(msg("e1", "alerts@hdfcbank.net"), Optional.of(base()), aiNone());

        assertThat(row.getStatus()).isEqualTo(GmailProcessedStatus.SKIPPED_NOT_TRANSACTION);
        assertThat(row.getExtractMismatch()).isEqualTo("ENGINE_ONLY");
        assertThat(compare("TEMPLATE", "engine_only", "gemini")).isEqualTo(1.0);
    }

    @Test
    void aiOnlyWhenEngineFoundNothing() {
        GmailProcessedMessage row = runWith(msg("o1", "alerts@hdfcbank.net"), Optional.empty(), aiBase());

        assertThat(row.getExtractTier()).isEqualTo("NONE");
        assertThat(row.getExtractAmount()).isNull();
        assertThat(row.getExtractVersion()).isEqualTo(TransactionExtractor.VERSION);
        assertThat(row.getExtractMismatch()).isEqualTo("AI_ONLY");
        assertThat(compare("NONE", "ai_only", "gemini")).isEqualTo(1.0);
    }

    @Test
    void bothNoneHasNoMismatch() {
        GmailProcessedMessage row = runWith(msg("b1", "alerts@hdfcbank.net"), Optional.empty(), aiNone());

        assertThat(row.getExtractMismatch()).isNull();
        assertThat(compare("NONE", "both_none", "gemini")).isEqualTo(1.0);
    }

    @Test
    void aiFailedIsRecordedAndTheFailureOutcomeIsUnchanged() {
        GmailProcessedMessage row = runWith(msg("f1", "alerts@hdfcbank.net"), Optional.of(base()),
                GeminiExtractionResult.failure("boom"));

        assertThat(row.getStatus()).isEqualTo(GmailProcessedStatus.FAILED_RETRYABLE);
        assertThat(row.getExtractMismatch()).isEqualTo("AI_FAILED");
        assertThat(row.getExtractTier()).isEqualTo("TEMPLATE");
        assertThat(row.getExtractAmount()).isEqualByComparingTo("725.00");
        assertThat(compare("TEMPLATE", "ai_failed", "none")).isEqualTo(1.0);
    }

    @Test
    void providerTagIsProviderIdOnly() {
        runWith(msg("p1", "alerts@hdfcbank.net"), Optional.of(base()), aiBase().withProvider("groq/llama-3.3-70b"));

        assertThat(compare("TEMPLATE", "match", "groq")).isEqualTo(1.0);
    }

    @Test
    void throwingEngineLeavesTheOutcomeUnchanged() {
        GmailMessage m = msg("t1", "alerts@hdfcbank.net");
        when(engine.extract(m)).thenThrow(new IllegalStateException("engine bug"));
        when(geminiExtractor.extract(m)).thenReturn(aiBase());
        when(accountResolver.resolve(any(), any())).thenReturn(Optional.empty());

        GmailProcessedMessage row = run(m);

        assertThat(row.getStatus()).isEqualTo(GmailProcessedStatus.UNRESOLVED_ACCOUNT);
        assertThat(row.getExtractTier()).isNull();
        assertThat(row.getExtractVersion()).isNull();
        assertThat(row.getExtractMismatch()).isNull();
        assertThat(registry.find("financeos.gmail.extract.compare").counter()).isNull();
    }

    @Test
    void engineOffLeavesNoShadowValues() {
        ReflectionTestUtils.setField(service, "transactionExtractor", null);
        GmailProcessedMessage row = runWith(msg("n1", "alerts@hdfcbank.net"), Optional.of(base()), aiBase());

        assertThat(row.getStatus()).isEqualTo(GmailProcessedStatus.UNRESOLVED_ACCOUNT);
        assertThat(row.getExtractTier()).isNull();
        assertThat(row.getExtractMismatch()).isNull();
    }

    @Test
    void reprocessClearsThePreviousAttemptsValues() {
        GmailMessage m = msg("r1", "alerts@hdfcbank.net");
        GmailProcessedMessage row = new GmailProcessedMessage();
        row.setExtractTier("TEMPLATE");
        row.setExtractTemplate("x");
        row.setExtractVersion(2);
        row.setExtractAmount(BigDecimal.ONE);
        row.setExtractCurrency("INR");
        row.setExtractDirection("DEBIT");
        row.setExtractDate(DAY);
        row.setExtractLast4("1234");
        row.setExtractCounterparty("old");
        row.setExtractMismatch("amount");
        row.setExtractAiProvider("old/old");
        row.setExtractMergeTxn(UUID.randomUUID());

        service.clearExtractShadow(row);

        assertThat(row.getExtractTier()).isNull();
        assertThat(row.getExtractTemplate()).isNull();
        assertThat(row.getExtractVersion()).isNull();
        assertThat(row.getExtractAmount()).isNull();
        assertThat(row.getExtractCurrency()).isNull();
        assertThat(row.getExtractDirection()).isNull();
        assertThat(row.getExtractDate()).isNull();
        assertThat(row.getExtractLast4()).isNull();
        assertThat(row.getExtractCounterparty()).isNull();
        assertThat(row.getExtractMismatch()).isNull();
        assertThat(row.getExtractAiProvider()).isNull();
        assertThat(row.getExtractMergeTxn()).isNull();
    }

    private static void fillOldValues(GmailProcessedMessage row) {
        row.setExtractTier("TEMPLATE");
        row.setExtractVersion(2);
        row.setExtractAmount(BigDecimal.ONE);
        row.setExtractMismatch("amount");
        row.setExtractAiProvider("old/old");
        row.setExtractMergeTxn(UUID.randomUUID());
    }

    private GmailProcessedMessage runWithOldValues(GmailMessage m) {
        // run() creates the row; pre-fill it through the repository mock on first findById.
        when(gmailEngine.listMessageIds(eq(connection), anyString())).thenReturn(List.of(m.messageId()));
        when(processedMessageRepository.findExistingMessageIds(eq(connection.getId()), anyCollection())).thenReturn(List.of());
        GmailProcessedMessage row = new GmailProcessedMessage();
        row.setId(UUID.randomUUID());
        row.setConnection(connection);
        row.setUser(user);
        row.setGmailMessageId(m.messageId());
        row.setStatus(GmailProcessedStatus.DISCOVERED);
        row.setDiscoveredAt(Instant.now());
        fillOldValues(row);
        when(processedMessageRepository.countByConnectionIdAndStatusIn(eq(connection.getId()), anyCollection())).thenReturn(1L, 0L);
        when(processedMessageRepository.findPendingForDrain(eq(connection.getId()), any(), any())).thenReturn(List.of(row));
        when(processedMessageRepository.findById(row.getId())).thenReturn(Optional.of(row));
        when(gmailEngine.fetchMessageDetails(connection, m.messageId())).thenReturn(m);
        service.syncConnection(connection);
        return row;
    }

    @Test
    void reprocessAsDuplicateClearsOldValues() {
        GmailMessage m = msg("d1", "alerts@hdfcbank.net");
        when(transactionRepository.existsByUserIdAndSourceMessageId(user.getId(), "d1")).thenReturn(true);

        GmailProcessedMessage row = runWithOldValues(m);

        assertThat(row.getStatus()).isEqualTo(GmailProcessedStatus.SKIPPED_DUPLICATE);
        assertThat(row.getExtractTier()).isNull();
        assertThat(row.getExtractVersion()).isNull();
        assertThat(row.getExtractAmount()).isNull();
        assertThat(row.getExtractMismatch()).isNull();
        assertThat(row.getExtractAiProvider()).isNull();
        assertThat(row.getExtractMergeTxn()).isNull();
    }

    @Test
    void reprocessAsSenderDisabledClearsOldValues() {
        GmailProcessedMessage row = runWithOldValues(msg("d2", "promo@elsewhere.example"));

        assertThat(row.getStatus()).isEqualTo(GmailProcessedStatus.SKIPPED_SENDER_DISABLED);
        assertThat(row.getExtractTier()).isNull();
        assertThat(row.getExtractMismatch()).isNull();
        assertThat(row.getExtractMergeTxn()).isNull();
    }

    @Test
    void metricsFailureStillKeepsFieldsAndRunsTheMergeCheck() {
        UUID twin = UUID.randomUUID();
        stubCandidates(twin, "alerts@hdfcbank.net");
        ObservabilityMetrics broken = mock(ObservabilityMetrics.class);
        doThrow(new IllegalStateException("metrics")).when(broken).recordGmailExtractCompare(any(), any(), any());
        ReflectionTestUtils.setField(service, "observabilityMetrics", broken);

        GmailProcessedMessage row = runWith(msg("mf1", "no-reply@cred.club"), Optional.of(base()), aiBase());

        assertThat(row.getExtractTier()).isEqualTo("TEMPLATE");
        assertThat(row.getExtractMergeTxn()).isEqualTo(twin);
    }

    @Test
    void mergeQueryRunsOutsideTheMessageTransactionAndItsFailureCannotChangeTheOutcome() {
        int[] openAtQuery = {-1};
        when(processedMessageRepository.findMergeCandidates(any(), any(), any(), any(), any(), any())).thenAnswer(i -> {
            openAtQuery[0] = openTx;
            throw new IllegalStateException("db");
        });

        GmailProcessedMessage row = runWith(msg("tx1", "no-reply@cred.club"), Optional.of(base()), aiBase());

        // exactly one open transaction (the merge check's own), never nested inside the message transaction
        assertThat(openAtQuery[0]).isEqualTo(1);
        assertThat(maxOpenTx).isEqualTo(1);
        assertThat(row.getStatus()).isEqualTo(GmailProcessedStatus.UNRESOLVED_ACCOUNT);
        assertThat(openTx).isZero();
    }

    // ---- merge check ----

    private static GmailProcessedMessageRepository.MergeCandidate candidate(UUID txnId, String sender) {
        return new GmailProcessedMessageRepository.MergeCandidate() {
            public UUID getTransactionId() { return txnId; }
            public String getSenderAddress() { return sender; }
        };
    }

    private void stubCandidates(UUID txnId, String sender) {
        when(processedMessageRepository.findMergeCandidates(eq(user.getId()), anyString(), any(), any(), any(), any()))
                .thenReturn(List.of(candidate(txnId, sender)));
    }

    @Test
    void providerMailFindsItsIssuerTwin() {
        UUID twin = UUID.randomUUID();
        stubCandidates(twin, "alerts@hdfcbank.net");
        GmailMessage m = msg("c1", "no-reply@cred.club");

        GmailProcessedMessage row = runWith(m, Optional.of(base()), aiBase());

        assertThat(row.getExtractMergeTxn()).isEqualTo(twin);
        assertThat(merge("provider_after_issuer")).isEqualTo(1.0);
        verify(processedMessageRepository).findMergeCandidates(user.getId(), "c1", new BigDecimal("725.00"),
                com.financeos.domain.transaction.TransactionType.DEBIT, DAY.minusDays(2), DAY.plusDays(2));
    }

    @Test
    void issuerMailFindsTheProviderTwinIncludingSubdomains() {
        UUID twin = UUID.randomUUID();
        stubCandidates(twin, "orders@mail.amazonpay.in");

        GmailProcessedMessage row = runWith(msg("c2", "alerts@hdfcbank.net"), Optional.of(base()), aiBase());

        assertThat(row.getExtractMergeTxn()).isEqualTo(twin);
        assertThat(merge("issuer_after_provider")).isEqualTo(1.0);
    }

    @Test
    void twoIssuerMailsDoNotMerge() {
        stubCandidates(UUID.randomUUID(), "alerts@icicibank.com");

        GmailProcessedMessage row = runWith(msg("c3", "alerts@hdfcbank.net"), Optional.of(base()), aiBase());

        assertThat(row.getExtractMergeTxn()).isNull();
        assertThat(registry.find("financeos.gmail.extract.merge").counter()).isNull();
    }

    @Test
    void twoProviderMailsDoNotMerge() {
        stubCandidates(UUID.randomUUID(), "x@paytm.com");

        GmailProcessedMessage row = runWith(msg("c4", "no-reply@cred.club"), Optional.of(base()), aiBase());

        assertThat(row.getExtractMergeTxn()).isNull();
    }

    @Test
    void searchWindowIsTwoDaysAndAmountExact() {
        // The repository applies amount and date; the service must ask for exactly the engine's values.
        runWith(msg("c5", "alerts@hdfcbank.net"), Optional.of(eng("99.50", "INR", Direction.CREDIT, DAY, "X", null)),
                ai("99.50", "INR", "CREDIT", DAY, "X", null));

        verify(processedMessageRepository).findMergeCandidates(user.getId(), "c5", new BigDecimal("99.50"),
                com.financeos.domain.transaction.TransactionType.CREDIT, DAY.minusDays(2), DAY.plusDays(2));
        verify(processedMessageRepository, never()).findMergeCandidatesForAccount(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void engineDateNullSkipsTheCheck() {
        runWith(msg("c6", "no-reply@cred.club"), Optional.of(eng("725.00", "INR", Direction.DEBIT, null, "X", null)), aiBase());

        verify(processedMessageRepository, never()).findMergeCandidates(any(), any(), any(), any(), any(), any());
    }

    @Test
    void noEngineResultSkipsTheCheck() {
        runWith(msg("c7", "no-reply@cred.club"), Optional.empty(), aiBase());

        verify(processedMessageRepository, never()).findMergeCandidates(any(), any(), any(), any(), any(), any());
    }

    @Test
    void resolvedAccountScopesTheQueryAndTheOutcomeIsUnchanged() {
        UUID twin = UUID.randomUUID();
        Account account = new Account();
        account.setId(UUID.randomUUID());
        GmailMessage m = msg("c8", "no-reply@cred.club");
        when(engine.extract(m)).thenReturn(Optional.of(base()));
        when(geminiExtractor.extract(m)).thenReturn(aiBase());
        when(accountResolver.resolve(any(), any())).thenReturn(Optional.of(new AccountResolver.ResolvedCard(account, null)));
        when(processedMessageRepository.findMergeCandidatesForAccount(eq(user.getId()), eq(account.getId()), eq("c8"), any(), any(), any(), any()))
                .thenReturn(List.of(candidate(twin, "alerts@hdfcbank.net")));

        GmailProcessedMessage row = run(m);

        assertThat(row.getExtractMergeTxn()).isEqualTo(twin);
        assertThat(row.getStatus()).isEqualTo(GmailProcessedStatus.ACCOUNT_NOT_OPTED_IN);
        verify(processedMessageRepository, never()).findMergeCandidates(any(), any(), any(), any(), any(), any());
        verifyNoInteractions(writer);
    }

    @Test
    void throwingMergeQueryLeavesTheOutcomeUnchanged() {
        when(processedMessageRepository.findMergeCandidates(any(), any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("db"));

        GmailProcessedMessage row = runWith(msg("c9", "no-reply@cred.club"), Optional.of(base()), aiBase());

        assertThat(row.getStatus()).isEqualTo(GmailProcessedStatus.UNRESOLVED_ACCOUNT);
        assertThat(row.getExtractMergeTxn()).isNull();
        assertThat(row.getExtractTier()).isEqualTo("TEMPLATE");
    }
}
