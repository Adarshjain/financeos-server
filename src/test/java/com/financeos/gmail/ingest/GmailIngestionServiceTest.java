package com.financeos.gmail.ingest;

import com.financeos.domain.account.Account;
import com.financeos.domain.categorization.CategorizationService;
import com.financeos.domain.transaction.Transaction;
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
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class GmailIngestionServiceTest {

    private GmailEngine gmailEngine;
    private GmailSyncCursorService cursorService;
    private GmailSenderRepository gmailSenderRepository;
    private AccountResolver accountResolver;
    private GmailTransactionWriter gmailTransactionWriter;
    private GeminiExtractor geminiExtractor;
    private GmailProcessedMessageRepository processedMessageRepository;
    private GmailBackfillDemandRepository backfillDemandRepository;
    private GmailIngestProperties ingestProperties;
    private StatementReconciliationService statementReconciliationService;
    private CategorizationService categorizationService;
    private TransactionRepository transactionRepository;
    private PlatformTransactionManager transactionManager;

    private GmailIngestionService gmailIngestionService;

    private GmailConnection connection;
    private UUID userId;
    private User user;

    @BeforeEach
    void setUp() {
        gmailEngine = mock(GmailEngine.class);
        cursorService = mock(GmailSyncCursorService.class);
        gmailSenderRepository = mock(GmailSenderRepository.class);
        accountResolver = mock(AccountResolver.class);
        gmailTransactionWriter = mock(GmailTransactionWriter.class);
        geminiExtractor = mock(GeminiExtractor.class);
        processedMessageRepository = mock(GmailProcessedMessageRepository.class);
        backfillDemandRepository = mock(GmailBackfillDemandRepository.class);
        ingestProperties = mock(GmailIngestProperties.class);
        statementReconciliationService = mock(StatementReconciliationService.class);
        categorizationService = mock(CategorizationService.class);
        transactionRepository = mock(TransactionRepository.class);
        transactionManager = mock(PlatformTransactionManager.class);

        TransactionStatus status = mock(TransactionStatus.class);
        when(transactionManager.getTransaction(any())).thenReturn(status);

        gmailIngestionService = new GmailIngestionService(
                gmailEngine,
                cursorService,
                gmailSenderRepository,
                accountResolver,
                gmailTransactionWriter,
                geminiExtractor,
                processedMessageRepository,
                backfillDemandRepository,
                ingestProperties,
                statementReconciliationService,
                categorizationService,
                transactionRepository,
                transactionManager
        );

        userId = UUID.randomUUID();
        user = new User();
        user.setId(userId);

        connection = new GmailConnection();
        connection.setId(UUID.randomUUID());
        connection.setUser(user);
        connection.setEmail("user@example.com");

        GmailSender sender = new GmailSender();
        sender.setId(UUID.randomUUID());
        sender.setUser(user);
        sender.setSenderAddress("alerts@bank.com");
        sender.setEnabled(true);

        when(gmailSenderRepository.findByUserIdAndEnabledTrue(userId)).thenReturn(List.of(sender));

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
        when(backfillDemandRepository.findById(userId)).thenReturn(Optional.empty());
    }

    @Test
    void testDiscoveryAndDrainLifecycle() {
        when(gmailEngine.listMessageIds(eq(connection), anyString())).thenReturn(List.of("msg-1"));
        when(processedMessageRepository.findExistingMessageIds(eq(connection.getId()), anyCollection())).thenReturn(List.of());

        GmailProcessedMessage discoveredRow = new GmailProcessedMessage();
        discoveredRow.setId(UUID.randomUUID());
        discoveredRow.setConnection(connection);
        discoveredRow.setUser(user);
        discoveredRow.setGmailMessageId("msg-1");
        discoveredRow.setStatus(GmailProcessedStatus.DISCOVERED);
        discoveredRow.setDiscoveredAt(Instant.now());

        when(processedMessageRepository.countByConnectionIdAndStatusIn(eq(connection.getId()), anyCollection())).thenReturn(1L, 0L);
        when(processedMessageRepository.findPendingForDrain(eq(connection.getId()), any(), any())).thenReturn(List.of(discoveredRow));
        when(processedMessageRepository.findById(discoveredRow.getId())).thenReturn(Optional.of(discoveredRow));

        GmailMessage msgDetails = new GmailMessage("msg-1", Instant.now(), "alerts@bank.com", "Alert Subject", "snippet", "body", null, List.of());
        when(gmailEngine.fetchMessageDetails(connection, "msg-1")).thenReturn(msgDetails);
        when(transactionRepository.existsByUserIdAndSourceMessageId(userId, "msg-1")).thenReturn(false);

        GeminiExtractionResult extSuccess = new GeminiExtractionResult(true, new java.math.BigDecimal("150.00"), "INR", "debit", LocalDate.now(), "Grocery store", "1234", 0.95, true, null);
        when(geminiExtractor.extract(msgDetails)).thenReturn(extSuccess);

        Account account = new Account("HDFC", com.financeos.domain.account.AccountType.credit_card);
        account.setIngestFromDate(LocalDate.now().minusDays(10));
        when(accountResolver.resolve("1234")).thenReturn(Optional.of(account));

        GmailProcessedMessage createdResult = new GmailProcessedMessage();
        createdResult.setStatus(GmailProcessedStatus.CREATED);
        Transaction txn = new Transaction();
        txn.setId(UUID.randomUUID());
        createdResult.setTransaction(txn);
        when(gmailTransactionWriter.writeTransaction(connection, "msg-1", extSuccess, account)).thenReturn(createdResult);

        SyncSummary summary = gmailIngestionService.syncConnection(connection);

        assertThat(summary.discovered()).isEqualTo(1);
        assertThat(summary.processed()).isEqualTo(1);
        assertThat(summary.created()).isEqualTo(1);
        verify(categorizationService).batchCategorize(anyList());
    }

    @Test
    void testOptInParking() {
        when(gmailEngine.listMessageIds(eq(connection), anyString())).thenReturn(List.of("msg-2"));
        when(processedMessageRepository.findExistingMessageIds(eq(connection.getId()), anyCollection())).thenReturn(List.of());

        GmailProcessedMessage row = new GmailProcessedMessage();
        row.setId(UUID.randomUUID());
        row.setConnection(connection);
        row.setUser(user);
        row.setGmailMessageId("msg-2");
        row.setStatus(GmailProcessedStatus.DISCOVERED);
        row.setDiscoveredAt(Instant.now());

        when(processedMessageRepository.countByConnectionIdAndStatusIn(eq(connection.getId()), anyCollection())).thenReturn(1L, 0L);
        when(processedMessageRepository.findPendingForDrain(eq(connection.getId()), any(), any())).thenReturn(List.of(row));
        when(processedMessageRepository.findById(row.getId())).thenReturn(Optional.of(row));

        GmailMessage msgDetails = new GmailMessage("msg-2", Instant.now(), "alerts@bank.com", "Alert Subject", "snippet", "body", null, List.of());
        when(gmailEngine.fetchMessageDetails(connection, "msg-2")).thenReturn(msgDetails);

        GeminiExtractionResult extSuccess = new GeminiExtractionResult(true, new java.math.BigDecimal("500.00"), "INR", "debit", LocalDate.now(), "Store", "4321", 0.95, true, null);
        when(geminiExtractor.extract(msgDetails)).thenReturn(extSuccess);

        Account unOptedAccount = new Account("SBI", com.financeos.domain.account.AccountType.bank_account);
        unOptedAccount.setIngestFromDate(null); // Not opted in
        when(accountResolver.resolve("4321")).thenReturn(Optional.of(unOptedAccount));

        SyncSummary summary = gmailIngestionService.syncConnection(connection);

        assertThat(summary.parked()).isEqualTo(1);
        assertThat(row.getStatus()).isEqualTo(GmailProcessedStatus.ACCOUNT_NOT_OPTED_IN);
    }

    @Test
    void testDuplicateGuard() {
        GmailProcessedMessage row = new GmailProcessedMessage();
        row.setId(UUID.randomUUID());
        row.setConnection(connection);
        row.setUser(user);
        row.setGmailMessageId("msg-3");
        row.setStatus(GmailProcessedStatus.DISCOVERED);
        row.setDiscoveredAt(Instant.now());

        when(processedMessageRepository.countByConnectionIdAndStatusIn(eq(connection.getId()), anyCollection())).thenReturn(1L, 0L);
        when(processedMessageRepository.findPendingForDrain(eq(connection.getId()), any(), any())).thenReturn(List.of(row));
        when(processedMessageRepository.findById(row.getId())).thenReturn(Optional.of(row));

        GmailMessage msgDetails = new GmailMessage("msg-3", Instant.now(), "alerts@bank.com", "Alert Subject", "snippet", "body", null, List.of());
        when(gmailEngine.fetchMessageDetails(connection, "msg-3")).thenReturn(msgDetails);
        when(transactionRepository.existsByUserIdAndSourceMessageId(userId, "msg-3")).thenReturn(true);

        SyncSummary summary = gmailIngestionService.syncConnection(connection);

        assertThat(summary.skipped()).isEqualTo(1);
        assertThat(row.getStatus()).isEqualTo(GmailProcessedStatus.SKIPPED_DUPLICATE);
    }

    @Test
    void testStaleProcessingSweep() {
        GmailProcessedMessage staleRow = new GmailProcessedMessage();
        staleRow.setId(UUID.randomUUID());
        staleRow.setConnection(connection);
        staleRow.setStatus(GmailProcessedStatus.PROCESSING);

        when(processedMessageRepository.findStaleProcessing(eq(connection.getId()), any())).thenReturn(List.of(staleRow));

        gmailIngestionService.syncConnection(connection);

        assertThat(staleRow.getStatus()).isEqualTo(GmailProcessedStatus.DISCOVERED);
        verify(processedMessageRepository).saveAll(anyList());
    }

    @Test
    void testStatementMultiTransactionCategorization_S1() {
        when(gmailEngine.listMessageIds(eq(connection), anyString())).thenReturn(List.of("stmt-msg"));
        when(processedMessageRepository.findExistingMessageIds(eq(connection.getId()), anyCollection())).thenReturn(List.of());

        GmailProcessedMessage row = new GmailProcessedMessage();
        row.setId(UUID.randomUUID());
        row.setConnection(connection);
        row.setUser(user);
        row.setGmailMessageId("stmt-msg");
        row.setStatus(GmailProcessedStatus.DISCOVERED);
        row.setDiscoveredAt(Instant.now());

        when(processedMessageRepository.countByConnectionIdAndStatusIn(eq(connection.getId()), anyCollection())).thenReturn(1L, 0L);
        when(processedMessageRepository.findPendingForDrain(eq(connection.getId()), any(), any())).thenReturn(List.of(row));
        when(processedMessageRepository.findById(row.getId())).thenReturn(Optional.of(row));

        com.financeos.gmail.internal.GmailAttachment att = new com.financeos.gmail.internal.GmailAttachment("att-1", "statement.pdf", "application/pdf", new byte[0]);
        GmailMessage msgDetails = new GmailMessage("stmt-msg", Instant.now(), "alerts@bank.com", "Bank Statement", "snippet", "body", null, List.of(att));
        when(gmailEngine.fetchMessageDetails(any(GmailConnection.class), eq("stmt-msg"))).thenReturn(msgDetails);

        Transaction t1 = new Transaction(); t1.setId(UUID.randomUUID());
        Transaction t2 = new Transaction(); t2.setId(UUID.randomUUID());
        Transaction t3 = new Transaction(); t3.setId(UUID.randomUUID());
        com.financeos.gmail.reconcile.ReconSummary recon = new com.financeos.gmail.reconcile.ReconSummary(3, 0, 0, List.of(t1, t2, t3));
        when(statementReconciliationService.reconcile(connection, msgDetails)).thenReturn(recon);

        gmailIngestionService.syncConnection(connection);

        ArgumentCaptor<List<Transaction>> captor = ArgumentCaptor.forClass(List.class);
        verify(categorizationService).batchCategorize(captor.capture());
        assertThat(captor.getValue()).containsExactly(t1, t2, t3);
    }

    @Test
    void testChunkedExistsCheck_S3() {
        List<String> largeMsgList = new ArrayList<>();
        for (int i = 0; i < 1200; i++) {
            largeMsgList.add("msg-id-" + i);
        }

        when(gmailEngine.listMessageIds(eq(connection), anyString())).thenReturn(largeMsgList);
        when(processedMessageRepository.findExistingMessageIds(eq(connection.getId()), anyCollection())).thenReturn(List.of());

        gmailIngestionService.syncConnection(connection);

        verify(processedMessageRepository, times(3)).findExistingMessageIds(eq(connection.getId()), anyCollection());
    }

    @Test
    void testLast4Normalization_S4() {
        GmailProcessedMessage row = new GmailProcessedMessage();
        row.setId(UUID.randomUUID());
        row.setConnection(connection);
        row.setUser(user);
        row.setGmailMessageId("msg-last4");
        row.setStatus(GmailProcessedStatus.DISCOVERED);
        row.setDiscoveredAt(Instant.now());

        when(processedMessageRepository.countByConnectionIdAndStatusIn(eq(connection.getId()), anyCollection())).thenReturn(1L, 0L);
        when(processedMessageRepository.findPendingForDrain(eq(connection.getId()), any(), any())).thenReturn(List.of(row));
        when(processedMessageRepository.findById(row.getId())).thenReturn(Optional.of(row));

        GmailMessage msgDetails = new GmailMessage("msg-last4", Instant.now(), "alerts@bank.com", "Alert", "snippet", "body", null, List.of());
        when(gmailEngine.fetchMessageDetails(any(GmailConnection.class), eq("msg-last4"))).thenReturn(msgDetails);

        // Gemini returns "XX1234" (length 6)
        GeminiExtractionResult extSuccess = new GeminiExtractionResult(true, new java.math.BigDecimal("100.00"), "INR", "debit", LocalDate.now(), "Store", "XX1234", 0.95, true, null);
        when(geminiExtractor.extract(msgDetails)).thenReturn(extSuccess);

        Account account = new Account("Card", com.financeos.domain.account.AccountType.credit_card);
        account.setIngestFromDate(LocalDate.now().minusDays(10));
        when(accountResolver.resolve("XX1234")).thenReturn(Optional.of(account));

        GmailProcessedMessage createdResult = new GmailProcessedMessage();
        createdResult.setStatus(GmailProcessedStatus.CREATED);
        when(gmailTransactionWriter.writeTransaction(connection, "msg-last4", extSuccess, account)).thenReturn(createdResult);

        gmailIngestionService.syncConnection(connection);

        // Verify extractedLast4 persisted on ledger row was truncated to "1234"
        assertThat(row.getExtractedLast4()).isEqualTo("1234");
        // Verify accountResolver was called with raw "XX1234"
        verify(accountResolver).resolve("XX1234");
    }
}
