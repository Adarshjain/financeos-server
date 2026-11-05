package com.financeos.gmail.ingest;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
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
import com.financeos.gmail.internal.GmailMessage;
import com.financeos.gmail.reconcile.StatementReconciliationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.UnexpectedRollbackException;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * One message whose database write fails must not fail the sync: its failure is recorded on its own ledger row
 * in a fresh transaction (one attempt, root cause as the error) and the next message is processed.
 * The "database" here keeps the committed state per row; a doomed transaction's saves are discarded, like a rollback.
 */
class GmailIngestionItemFailureTest {

    private static final String ORA = "ORA-02290: check constraint (FOS.CHK_TRANSACTIONS_AMOUNT) violated";
    private static final Instant RECEIVED = Instant.parse("2026-10-11T04:30:00Z");

    private GmailEngine gmailEngine;
    private GeminiExtractor geminiExtractor;
    private GmailTransactionWriter writer;
    private AccountResolver accountResolver;
    private GmailProcessedMessageRepository repo;
    private GmailIngestProperties props;
    private GmailIngestionService service;
    private GmailConnection connection;
    private User user;
    private UUID userId;
    private Account account;

    private final Map<UUID, GmailProcessedMessage> db = new LinkedHashMap<>();
    private final Set<UUID> committed = new HashSet<>();
    private boolean doomed;
    private ListAppender<ILoggingEvent> logs;
    private Logger serviceLogger;

    @BeforeEach
    void setUp() {
        gmailEngine = mock(GmailEngine.class);
        GmailSyncCursorService cursorService = mock(GmailSyncCursorService.class);
        GmailSenderRepository senderRepository = mock(GmailSenderRepository.class);
        accountResolver = mock(AccountResolver.class);
        writer = mock(GmailTransactionWriter.class);
        geminiExtractor = mock(GeminiExtractor.class);
        repo = mock(GmailProcessedMessageRepository.class);
        GmailBackfillDemandRepository backfillDemandRepository = mock(GmailBackfillDemandRepository.class);
        props = mock(GmailIngestProperties.class);
        PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
        when(txManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        // A doomed transaction (Hibernate marked it rollback-only) fails at commit, as in production.
        doAnswer(inv -> {
            if (doomed) {
                doomed = false;
                throw new UnexpectedRollbackException("Transaction silently rolled back because it has been marked as rollback-only");
            }
            return null;
        }).when(txManager).commit(any());
        doAnswer(inv -> { doomed = false; return null; }).when(txManager).rollback(any());

        service = new GmailIngestionService(gmailEngine, cursorService, senderRepository, accountResolver,
                writer, geminiExtractor, repo, backfillDemandRepository, props,
                mock(StatementReconciliationService.class), mock(CategorizationService.class),
                mock(TransactionRepository.class), txManager, mock(ParkedMessageSettler.class));

        userId = UUID.randomUUID();
        user = new User();
        user.setId(userId);
        connection = new GmailConnection();
        connection.setId(UUID.randomUUID());
        connection.setUser(user);
        connection.setEmail("me@example.com");
        GmailSender sender = new GmailSender();
        sender.setId(UUID.randomUUID());
        sender.setUser(user);
        sender.setSenderAddress("alerts@bank.com");
        sender.setEnabled(true);
        when(senderRepository.findByUserIdAndEnabledTrue(userId)).thenReturn(List.of(sender));
        GmailSyncCursor cursor = new GmailSyncCursor();
        cursor.setUser(user);
        cursor.setConnection(connection);
        cursor.setSender(sender);
        cursor.setLastListedAt(Instant.parse("2026-10-10T06:00:00Z"));
        cursor.setEarliestCoveredAt(Instant.parse("2026-09-01T00:00:00Z"));
        when(cursorService.getOrSeedCursors(connection)).thenReturn(List.of(cursor));

        when(props.getProcessBudget()).thenReturn(100);
        when(props.getBackfillProcessBudget()).thenReturn(500);
        when(props.getOverlapLapMinutes()).thenReturn(15);
        when(props.getStaleProcessingMinutes()).thenReturn(60);
        when(props.getRetryMaxAttempts()).thenReturn(5);
        when(backfillDemandRepository.findById(userId)).thenReturn(Optional.empty());
        when(repo.findExistingMessageIds(eq(connection.getId()), anyCollection())).thenReturn(List.of());
        when(repo.countByConnectionIdAndStatusIn(eq(connection.getId()), anyCollection())).thenReturn(0L);
        when(repo.findPendingForDrain(eq(connection.getId()), any(), any()))
                .thenAnswer(inv -> db.values().stream().map(this::copy).collect(Collectors.toList()));
        when(repo.findById(any(UUID.class))).thenAnswer(inv -> Optional.ofNullable(db.get(inv.<UUID>getArgument(0))).map(this::copy));
        when(repo.save(any(GmailProcessedMessage.class))).thenAnswer(inv -> {
            GmailProcessedMessage m = inv.getArgument(0);
            if (!doomed) {
                db.put(m.getId(), copy(m));
            }
            return m;
        });
        when(gmailEngine.listMessageIds(eq(connection), anyString())).thenReturn(List.of());

        account = new Account("HDFC", AccountType.credit_card);
        account.setId(UUID.randomUUID());
        account.setIngestFromDate(LocalDate.of(2026, 1, 1));

        serviceLogger = (Logger) LoggerFactory.getLogger(GmailIngestionService.class);
        serviceLogger.setLevel(Level.DEBUG);
        logs = new ListAppender<>();
        logs.start();
        serviceLogger.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        serviceLogger.detachAppender(logs);
        org.slf4j.MDC.clear();
    }

    private GmailProcessedMessage copy(GmailProcessedMessage s) {
        GmailProcessedMessage c = new GmailProcessedMessage();
        c.setId(s.getId());
        c.setConnection(s.getConnection());
        c.setUser(s.getUser());
        c.setGmailMessageId(s.getGmailMessageId());
        c.setStatus(s.getStatus());
        c.setDiscoveredAt(s.getDiscoveredAt());
        c.setAttemptCount(s.getAttemptCount());
        c.setError(s.getError());
        c.setNextRetryAt(s.getNextRetryAt());
        c.setInternalDate(s.getInternalDate());
        c.setAccount(s.getAccount());
        c.setTransaction(s.getTransaction());
        return c;
    }

    private void pending(String messageId, int attempts) {
        GmailProcessedMessage row = new GmailProcessedMessage();
        row.setId(UUID.randomUUID());
        row.setConnection(connection);
        row.setUser(user);
        row.setGmailMessageId(messageId);
        row.setStatus(GmailProcessedStatus.DISCOVERED);
        row.setDiscoveredAt(Instant.now());
        row.setAttemptCount(attempts);
        db.put(row.getId(), row);
        GmailMessage m = new GmailMessage(messageId, RECEIVED, "alerts@bank.com", "You spent Rs 450", "snippet", "body", null, List.of());
        when(gmailEngine.fetchMessageDetails(connection, messageId)).thenReturn(m);
        GeminiExtractionResult ext = new GeminiExtractionResult(true, new BigDecimal("450"), "INR", "debit",
                LocalDate.of(2026, 10, 11), "SWIGGY", "1234", 0.95, true, null);
        when(geminiExtractor.extract(m)).thenReturn(ext);
        when(accountResolver.resolve(eq("1234"), any(), any())).thenReturn(Optional.of(new AccountResolver.ResolvedCard(account, null)));
    }

    private GmailProcessedMessage row(String messageId) {
        return db.values().stream().filter(r -> r.getGmailMessageId().equals(messageId)).findFirst().orElseThrow();
    }

    /** Message {@code ok}: the writer creates a transaction. */
    private void writerCreatesFor(String messageId) {
        when(writer.writeTransaction(eq(connection), eq(messageId), any(), eq(account), any())).thenAnswer(inv -> {
            GmailProcessedMessage r = new GmailProcessedMessage();
            r.setStatus(GmailProcessedStatus.CREATED);
            Transaction t = new Transaction();
            t.setId(UUID.randomUUID());
            t.setAccount(account);
            t.setDate(LocalDate.of(2026, 10, 11));
            t.setAmount(new BigDecimal("450"));
            t.setSourcedDescription("SWIGGY");
            t.setType(TransactionType.DEBIT);
            r.setTransaction(t);
            return r;
        });
    }

    /** The writer's save fails the way the incident did, and Hibernate dooms the transaction. */
    private void writerFailsWithConstraintFor(String messageId) {
        when(writer.writeTransaction(eq(connection), eq(messageId), any(), eq(account), any())).thenAnswer(inv -> {
            doomed = true;
            throw new DataIntegrityViolationException("could not execute statement", new SQLException(ORA));
        });
    }

    @Test
    void aConstraintViolationOnTheSecondMessageDoesNotStopTheBatchOrFailTheSync() {
        pending("m1", 0);
        pending("m2", 0);
        pending("m3", 0);
        writerCreatesFor("m1");
        writerFailsWithConstraintFor("m2");
        writerCreatesFor("m3");

        SyncSummary summary = service.syncConnection(connection);

        assertThat(row("m1").getStatus()).isEqualTo(GmailProcessedStatus.CREATED);
        assertThat(row("m3").getStatus()).isEqualTo(GmailProcessedStatus.CREATED);
        GmailProcessedMessage failed = row("m2");
        assertThat(failed.getStatus()).isEqualTo(GmailProcessedStatus.FAILED_RETRYABLE);
        assertThat(failed.getAttemptCount()).isEqualTo(1);
        assertThat(failed.getError()).contains(ORA).doesNotContain("silently rolled back");
        assertThat(failed.getNextRetryAt()).isNotNull();
        assertThat(summary.processed()).isEqualTo(3);
        assertThat(summary.created()).isEqualTo(2);
        assertThat(summary.failedRetryable()).isEqualTo(1);
        assertThat(summary.failedPermanent()).isZero();
        assertThat(summary.statusCounts()).containsEntry(GmailProcessedStatus.FAILED_RETRYABLE, 1);
    }

    @Test
    void atTheMaxAttemptsADatabaseFailureBecomesPermanent() {
        pending("m1", 4);
        writerFailsWithConstraintFor("m1");

        SyncSummary summary = service.syncConnection(connection);

        GmailProcessedMessage failed = row("m1");
        assertThat(failed.getStatus()).isEqualTo(GmailProcessedStatus.FAILED_PERMANENT);
        assertThat(failed.getAttemptCount()).isEqualTo(5);
        assertThat(failed.getNextRetryAt()).isNull();
        assertThat(failed.getError()).contains(ORA);
        assertThat(summary.failedPermanent()).isEqualTo(1);
        assertThat(summary.failedRetryable()).isZero();
    }

    @Test
    void anExceptionThatEscapesTheTransactionIsRecordedToo() {
        pending("m1", 0);
        pending("m2", 0);
        writerCreatesFor("m2");
        int[] calls = {0};
        when(repo.save(any(GmailProcessedMessage.class))).thenAnswer(inv -> {
            GmailProcessedMessage m = inv.getArgument(0);
            // m1's first save (PROCESSING, before processMessage's try) escapes the transaction; the fresh-tx save works
            if ("m1".equals(m.getGmailMessageId()) && calls[0]++ == 0) {
                throw new DataIntegrityViolationException("flush failed", new SQLException(ORA));
            }
            db.put(m.getId(), copy(m));
            return m;
        });

        SyncSummary summary = service.syncConnection(connection);

        GmailProcessedMessage failed = row("m1");
        assertThat(failed.getStatus()).isEqualTo(GmailProcessedStatus.FAILED_RETRYABLE);
        assertThat(failed.getAttemptCount()).isEqualTo(1);
        assertThat(failed.getError()).contains(ORA);
        assertThat(row("m2").getStatus()).isEqualTo(GmailProcessedStatus.CREATED);
        assertThat(summary.failedRetryable()).isEqualTo(1);
    }

    @Test
    void aNonDatabaseFailureStillRecordsExactlyOneAttempt() {
        pending("m1", 0);
        when(geminiExtractor.extract(any())).thenThrow(new IllegalStateException("LLM boom"));

        SyncSummary summary = service.syncConnection(connection);

        GmailProcessedMessage failed = row("m1");
        assertThat(failed.getStatus()).isEqualTo(GmailProcessedStatus.FAILED_RETRYABLE);
        assertThat(failed.getAttemptCount()).isEqualTo(1);
        assertThat(failed.getError()).isEqualTo("LLM boom");
        assertThat(failed.getNextRetryAt()).isNotNull();
        assertThat(summary.failedRetryable()).isEqualTo(1);
    }

    @Test
    void ifRecordingTheFailureItselfFailsTheLoopContinues() {
        pending("m1", 0);
        pending("m2", 0);
        writerFailsWithConstraintFor("m1");
        writerCreatesFor("m2");
        // Recording in the fresh transaction fails too: its first FAILED_RETRYABLE save throws.
        when(repo.save(any(GmailProcessedMessage.class))).thenAnswer(inv -> {
            GmailProcessedMessage m = inv.getArgument(0);
            if (m.getStatus() == GmailProcessedStatus.FAILED_RETRYABLE) {
                throw new IllegalStateException("db down");
            }
            if (!doomed) {
                db.put(m.getId(), copy(m));
            }
            return m;
        });

        SyncSummary summary = service.syncConnection(connection);

        assertThat(row("m2").getStatus()).isEqualTo(GmailProcessedStatus.CREATED);
        assertThat(summary.processed()).isEqualTo(2);
        assertThat(summary.failedRetryable()).isEqualTo(1);
    }

    @Test
    void theFailureIsLoggedOnceAsAStructuredWarnNamingTheCulprit() {
        org.slf4j.MDC.put("jobName", "gmail-sync");
        org.slf4j.MDC.put("jobId", "11111111-2222-3333-4444-555555555555");
        pending("m2", 0);
        writerFailsWithConstraintFor("m2");

        service.syncConnection(connection);

        List<ILoggingEvent> events = logs.list.stream()
                .filter(e -> e.getFormattedMessage().startsWith("Message could not be processed")).toList();
        assertThat(events).hasSize(1);
        ILoggingEvent e = events.get(0);
        assertThat(e.getLevel()).isEqualTo(Level.WARN);
        String args = Arrays.stream(e.getArgumentArray()).map(String::valueOf).collect(Collectors.joining(" "));
        assertThat(args).contains("event=ingest.item_failed", "jobName=gmail-sync",
                "jobId=11111111-2222-3333-4444-555555555555", "userId=" + userId, "itemType=gmail_message",
                "itemId=m2", "ledgerId=" + row("m2").getId(), "errorClass=SQLException", "attempt=1",
                "status=FAILED_RETRYABLE", ORA);
    }
}
