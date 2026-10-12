package com.financeos.gmail.ingest;

import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountType;
import com.financeos.domain.categorization.CategorizationService;
import com.financeos.domain.transaction.Transaction;
import com.financeos.domain.transaction.TransactionRepository;
import com.financeos.domain.transaction.TransactionType;
import com.financeos.domain.user.User;
import com.financeos.gmail.domain.*;
import com.financeos.gmail.engine.GmailEngine;
import com.financeos.gmail.ingest.gemini.GeminiExtractionResult;
import com.financeos.gmail.ingest.gemini.GeminiExtractor;
import com.financeos.gmail.internal.GmailAttachment;
import com.financeos.gmail.internal.GmailMessage;
import com.financeos.gmail.reconcile.ReconSummary;
import com.financeos.gmail.reconcile.StatementReconciliationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** The GMAIL_SYNC result says what was searched and what happened to each email, not only counts. */
class GmailIngestionRunDetailTest {

    private static final Instant LAST_LISTED = Instant.parse("2026-10-10T06:00:00Z");
    private static final Instant RECEIVED = Instant.parse("2026-10-11T04:30:00Z");

    private GmailEngine gmailEngine;
    private GmailSyncCursorService cursorService;
    private AccountResolver accountResolver;
    private GmailTransactionWriter gmailTransactionWriter;
    private GeminiExtractor geminiExtractor;
    private GmailProcessedMessageRepository processedMessageRepository;
    private GmailBackfillDemandRepository backfillDemandRepository;
    private StatementReconciliationService statementReconciliationService;
    private TransactionRepository transactionRepository;
    private GmailIngestionService service;

    private GmailConnection connection;
    private User user;
    private UUID userId;
    private GmailSyncCursor cursor;
    private final Map<String, GmailProcessedMessage> rows = new LinkedHashMap<>();

    @BeforeEach
    void setUp() {
        gmailEngine = mock(GmailEngine.class);
        cursorService = mock(GmailSyncCursorService.class);
        GmailSenderRepository senderRepository = mock(GmailSenderRepository.class);
        accountResolver = mock(AccountResolver.class);
        gmailTransactionWriter = mock(GmailTransactionWriter.class);
        geminiExtractor = mock(GeminiExtractor.class);
        processedMessageRepository = mock(GmailProcessedMessageRepository.class);
        backfillDemandRepository = mock(GmailBackfillDemandRepository.class);
        GmailIngestProperties props = mock(GmailIngestProperties.class);
        statementReconciliationService = mock(StatementReconciliationService.class);
        transactionRepository = mock(TransactionRepository.class);
        PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
        when(txManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));

        service = new GmailIngestionService(gmailEngine, cursorService, senderRepository, accountResolver,
                gmailTransactionWriter, geminiExtractor, processedMessageRepository, backfillDemandRepository, props,
                statementReconciliationService, mock(CategorizationService.class), transactionRepository, txManager);

        userId = UUID.randomUUID();
        user = new User();
        user.setId(userId);
        connection = new GmailConnection();
        connection.setId(UUID.randomUUID());
        connection.setUser(user);
        connection.setEmail("me@example.com");

        GmailSender alerts = sender("alerts@bank.com");
        GmailSender statements = sender("statements@bank.com");
        when(senderRepository.findByUserIdAndEnabledTrue(userId)).thenReturn(List.of(alerts, statements));

        cursor = new GmailSyncCursor();
        cursor.setUser(user);
        cursor.setConnection(connection);
        cursor.setSender(alerts);
        cursor.setLastListedAt(LAST_LISTED);
        cursor.setEarliestCoveredAt(Instant.parse("2026-09-01T00:00:00Z"));
        when(cursorService.getOrSeedCursors(connection)).thenReturn(List.of(cursor));

        when(props.getProcessBudget()).thenReturn(100);
        when(props.getBackfillProcessBudget()).thenReturn(500);
        when(props.getOverlapLapMinutes()).thenReturn(15);
        when(props.getStaleProcessingMinutes()).thenReturn(60);
        when(props.getRetryMaxAttempts()).thenReturn(5);
        when(props.getMaxBackfillDays()).thenReturn(365);
        when(backfillDemandRepository.findById(userId)).thenReturn(Optional.empty());
        when(processedMessageRepository.findExistingMessageIds(eq(connection.getId()), anyCollection())).thenReturn(List.of());
        when(processedMessageRepository.countByConnectionIdAndStatusIn(eq(connection.getId()), anyCollection())).thenReturn(0L);
        when(processedMessageRepository.findPendingForDrain(eq(connection.getId()), any(), any()))
                .thenAnswer(inv -> new ArrayList<>(rows.values()));
        when(gmailEngine.listMessageIds(eq(connection), anyString())).thenReturn(List.of());
    }

    private GmailSender sender(String address) {
        GmailSender s = new GmailSender();
        s.setId(UUID.randomUUID());
        s.setUser(user);
        s.setSenderAddress(address);
        s.setEnabled(true);
        return s;
    }

    private GmailProcessedMessage pending(String messageId) {
        GmailProcessedMessage row = new GmailProcessedMessage();
        row.setId(UUID.randomUUID());
        row.setConnection(connection);
        row.setUser(user);
        row.setGmailMessageId(messageId);
        row.setStatus(GmailProcessedStatus.DISCOVERED);
        row.setDiscoveredAt(Instant.now());
        when(processedMessageRepository.findById(row.getId())).thenReturn(Optional.of(row));
        rows.put(messageId, row);
        return row;
    }

    private GmailMessage message(String id, String from, String subject, List<GmailAttachment> attachments) {
        GmailMessage m = new GmailMessage(id, RECEIVED, from, subject, "snippet", "body", null, attachments);
        when(gmailEngine.fetchMessageDetails(connection, id)).thenReturn(m);
        return m;
    }

    private Account optedIn(String name) {
        Account account = new Account(name, AccountType.credit_card);
        account.setId(UUID.randomUUID());
        account.setIngestFromDate(LocalDate.of(2026, 1, 1));
        return account;
    }

    private Transaction txn(Account account, String description, String amount) {
        Transaction t = new Transaction();
        t.setId(UUID.randomUUID());
        t.setAccount(account);
        t.setDate(LocalDate.of(2026, 10, 11));
        t.setAmount(new BigDecimal(amount));
        t.setSourcedDescription(description);
        t.setType(TransactionType.DEBIT);
        return t;
    }

    private SyncSummary.MessageResult resultFor(SyncSummary summary, String messageId) {
        return summary.messages().stream().filter(m -> m.gmailMessageId().equals(messageId)).findFirst().orElseThrow();
    }

    @Test
    void theRunSaysWhichMailboxSendersAndWindowWereSearched() {
        when(gmailEngine.listMessageIds(eq(connection), anyString())).thenReturn(List.of("new-1", "known-1"));
        when(processedMessageRepository.findExistingMessageIds(eq(connection.getId()), anyCollection())).thenReturn(List.of("known-1"));

        SyncSummary summary = service.syncConnection(connection);

        assertThat(summary.mailbox()).isEqualTo("me@example.com");
        assertThat(summary.listed()).isEqualTo(2);
        assertThat(summary.discovered()).isEqualTo(1);
        assertThat(summary.searchedSince()).isEqualTo(LAST_LISTED.minus(Duration.ofMinutes(15)));
        assertThat(summary.backfilledTo()).isNull();
        assertThat(summary.senders()).containsExactly("alerts@bank.com", "statements@bank.com");
        assertThat(summary.budget()).isEqualTo(100);
        assertThat(summary.messages()).isEmpty();
        assertThat(summary.messagesTruncated()).isZero();
    }

    @Test
    void aBackfillRunReportsTheDateItListedDownTo() {
        LocalDate floor = com.financeos.core.time.AppTime.today().minusDays(60);
        when(backfillDemandRepository.findById(userId)).thenReturn(Optional.of(new GmailBackfillDemand(user, floor)));
        when(processedMessageRepository.countByConnectionIdAndStatusIn(eq(connection.getId()), anyCollection())).thenReturn(300L, 0L);
        cursor.setEarliestCoveredAt(Instant.now());

        SyncSummary summary = service.syncConnection(connection);

        assertThat(summary.backfilledTo()).isEqualTo(floor);
        assertThat(summary.budget()).isEqualTo(500);
    }

    @Test
    void anEmailListedByBothForwardAndBackfillPassesCountsOnce() {
        LocalDate floor = com.financeos.core.time.AppTime.today().minusDays(60);
        when(backfillDemandRepository.findById(userId)).thenReturn(Optional.of(new GmailBackfillDemand(user, floor)));
        cursor.setEarliestCoveredAt(Instant.now());
        when(gmailEngine.listMessageIds(eq(connection), anyString())).thenReturn(List.of("a", "b"), List.of("b", "c"));

        SyncSummary summary = service.syncConnection(connection);

        verify(gmailEngine, times(2)).listMessageIds(eq(connection), anyString());
        assertThat(summary.listed()).isEqualTo(3);
    }

    @Test
    void anAlertNamesTheAccountAndTheTransactionItCreated() {
        GmailProcessedMessage row = pending("alert-1");
        GmailMessage m = message("alert-1", "HDFC Alerts <alerts@bank.com>", "You spent Rs 450", List.of());
        GeminiExtractionResult ext = new GeminiExtractionResult(true, new BigDecimal("450"), "INR", "debit",
                LocalDate.of(2026, 10, 11), "SWIGGY", "1234", 0.95, true, null);
        when(geminiExtractor.extract(m)).thenReturn(ext);
        Account account = optedIn("HDFC Regalia");
        when(accountResolver.resolve("1234")).thenReturn(Optional.of(new AccountResolver.ResolvedCard(account, null)));
        Transaction created = txn(account, "SWIGGY", "450");
        when(gmailTransactionWriter.writeTransaction(connection, "alert-1", ext, account, null)).thenAnswer(inv -> {
            row.setAccount(account);
            row.setStatus(GmailProcessedStatus.CREATED);
            row.setTransaction(created);
            return row;
        });

        SyncSummary summary = service.syncConnection(connection);

        SyncSummary.MessageResult r = resultFor(summary, "alert-1");
        assertThat(r.ledgerId()).isEqualTo(row.getId());
        assertThat(r.status()).isEqualTo(GmailProcessedStatus.CREATED);
        assertThat(r.sender()).isEqualTo("alerts@bank.com");
        assertThat(r.subject()).isEqualTo("You spent Rs 450");
        assertThat(r.receivedAt()).isEqualTo(RECEIVED);
        assertThat(r.accountId()).isEqualTo(account.getId());
        assertThat(r.accountName()).isEqualTo("HDFC Regalia");
        assertThat(r.last4()).isEqualTo("1234");
        assertThat(r.createdCount()).isEqualTo(1);
        assertThat(r.transactions()).containsExactly(new SyncSummary.TxnRef(
                created.getId(), LocalDate.of(2026, 10, 11), new BigDecimal("450"), "SWIGGY", "DEBIT"));
        assertThat(summary.statusCounts()).containsEntry(GmailProcessedStatus.CREATED, 1);
    }

    @Test
    void aStatementListsItsFileMatchesAndACappedSetOfNewTransactions() {
        GmailProcessedMessage row = pending("stmt-1");
        GmailAttachment pdf = new GmailAttachment("att", "Oct-2026.pdf", "application/pdf", new byte[0]);
        GmailMessage m = message("stmt-1", "statements@bank.com", "Your October statement", List.of(pdf));
        Account account = optedIn("Amex Plat");
        List<Transaction> created = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            created.add(txn(account, "LINE " + i, "10" + i));
        }
        when(statementReconciliationService.reconcile(connection, m)).thenAnswer(inv -> {
            row.setAccount(account);
            row.setStatus(GmailProcessedStatus.RECONCILED);
            return new ReconSummary(7, 3, 0, created, null, null, "Oct-2026.pdf", null, false);
        });

        SyncSummary summary = service.syncConnection(connection);

        SyncSummary.MessageResult r = resultFor(summary, "stmt-1");
        assertThat(r.status()).isEqualTo(GmailProcessedStatus.RECONCILED);
        assertThat(r.attachmentFilename()).isEqualTo("Oct-2026.pdf");
        assertThat(r.accountName()).isEqualTo("Amex Plat");
        assertThat(r.createdCount()).isEqualTo(7);
        assertThat(r.matchedCount()).isEqualTo(3);
        assertThat(r.transactions()).hasSize(SyncSummary.MAX_TRANSACTIONS_PER_MESSAGE);
        assertThat(r.transactions().get(0).id()).isEqualTo(created.get(0).getId());
    }

    @Test
    void anUnreadableStatementCarriesTheReconcilersReasonAndLast4() {
        GmailProcessedMessage row = pending("stmt-2");
        GmailAttachment pdf = new GmailAttachment("att", "stmt.pdf", "application/pdf", new byte[0]);
        GmailMessage m = message("stmt-2", "statements@bank.com", "Statement", List.of(pdf));
        when(statementReconciliationService.reconcile(connection, m)).thenAnswer(inv -> {
            row.setStatus(GmailProcessedStatus.UNRESOLVED_ACCOUNT);
            return new ReconSummary(0, 0, 1, List.of(), GmailProcessedStatus.UNRESOLVED_ACCOUNT,
                    "No single account matches statement number XX9876", "stmt.pdf", "9876", false);
        });

        SyncSummary summary = service.syncConnection(connection);

        SyncSummary.MessageResult r = resultFor(summary, "stmt-2");
        assertThat(r.status()).isEqualTo(GmailProcessedStatus.UNRESOLVED_ACCOUNT);
        assertThat(r.last4()).isEqualTo("9876");
        assertThat(r.error()).isEqualTo("No single account matches statement number XX9876");
        assertThat(r.accountId()).isNull();
        assertThat(summary.parked()).isEqualTo(1);
    }

    @Test
    void anUnmatchedCardIsParkedWithItsLast4() {
        pending("alert-2");
        GmailMessage m = message("alert-2", "alerts@bank.com", "Spent", List.of());
        when(geminiExtractor.extract(m)).thenReturn(new GeminiExtractionResult(true, new BigDecimal("90"), "INR", "debit",
                LocalDate.of(2026, 10, 11), "CAFE", "5555", 0.9, true, null));
        when(accountResolver.resolve("5555")).thenReturn(Optional.empty());

        SyncSummary summary = service.syncConnection(connection);

        SyncSummary.MessageResult r = resultFor(summary, "alert-2");
        assertThat(r.status()).isEqualTo(GmailProcessedStatus.UNRESOLVED_ACCOUNT);
        assertThat(r.last4()).isEqualTo("5555");
        assertThat(r.error()).contains("5555");
        assertThat(r.transactions()).isEmpty();
    }

    @Test
    void aRetryableFailureShowsTheErrorAttemptAndNextRetry() {
        pending("alert-3");
        GmailMessage m = message("alert-3", "alerts@bank.com", "Spent", List.of());
        when(geminiExtractor.extract(m)).thenThrow(new RuntimeException("LLM timed out"));

        Instant before = Instant.now();
        SyncSummary summary = service.syncConnection(connection);

        SyncSummary.MessageResult r = resultFor(summary, "alert-3");
        assertThat(r.status()).isEqualTo(GmailProcessedStatus.FAILED_RETRYABLE);
        assertThat(r.error()).isEqualTo("LLM timed out");
        assertThat(r.attemptCount()).isEqualTo(1);
        assertThat(r.nextRetryAt()).isAfter(before);
        assertThat(summary.failedRetryable()).isEqualTo(1);
    }

    @Test
    void statusCountsSplitTheSkippedReasons() {
        pending("dup-1");
        message("dup-1", "alerts@bank.com", "Dup", List.of());
        when(transactionRepository.existsByUserIdAndSourceMessageId(userId, "dup-1")).thenReturn(true);
        pending("off-1");
        message("off-1", "promo@shop.com", "Sale", List.of());
        pending("off-2");
        message("off-2", "promo@shop.com", "Sale 2", List.of());

        SyncSummary summary = service.syncConnection(connection);

        assertThat(summary.skipped()).isEqualTo(3);
        assertThat(summary.statusCounts())
                .containsEntry(GmailProcessedStatus.SKIPPED_DUPLICATE, 1)
                .containsEntry(GmailProcessedStatus.SKIPPED_SENDER_DISABLED, 2)
                .hasSize(2);
    }

    @Test
    void problemsComeFirstAndTheListIsCapped() {
        for (int i = 0; i < SyncSummary.MAX_MESSAGES + 2; i++) {
            pending("skip-" + i);
            message("skip-" + i, "promo@shop.com", "Promo " + i, List.of());
        }
        pending("fail-1");
        GmailMessage failing = message("fail-1", "alerts@bank.com", "Spent", List.of());
        when(geminiExtractor.extract(failing)).thenThrow(new RuntimeException("boom"));

        SyncSummary summary = service.syncConnection(connection);

        assertThat(summary.processed()).isEqualTo(SyncSummary.MAX_MESSAGES + 3);
        assertThat(summary.messages()).hasSize(SyncSummary.MAX_MESSAGES);
        assertThat(summary.messagesTruncated()).isEqualTo(3);
        assertThat(summary.messages().get(0).gmailMessageId()).isEqualTo("fail-1");
        assertThat(summary.statusCounts()).containsEntry(GmailProcessedStatus.SKIPPED_SENDER_DISABLED, SyncSummary.MAX_MESSAGES + 2);
    }

    @Test
    void noEnabledSendersStillNamesTheMailbox() {
        GmailSenderRepository none = mock(GmailSenderRepository.class);
        when(none.findByUserIdAndEnabledTrue(userId)).thenReturn(List.of());
        PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
        GmailIngestionService idle = new GmailIngestionService(gmailEngine, cursorService, none, accountResolver,
                gmailTransactionWriter, geminiExtractor, processedMessageRepository, backfillDemandRepository,
                mock(GmailIngestProperties.class), statementReconciliationService, mock(CategorizationService.class),
                transactionRepository, txManager);

        SyncSummary summary = idle.syncConnection(connection);

        assertThat(summary.mailbox()).isEqualTo("me@example.com");
        assertThat(summary.senders()).isEmpty();
        assertThat(summary.messages()).isEmpty();
        verify(gmailEngine, never()).listMessageIds(any(), anyString());
    }
}
