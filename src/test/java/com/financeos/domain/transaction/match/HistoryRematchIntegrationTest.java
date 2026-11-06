package com.financeos.domain.transaction.match;

import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountBankDetails;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.account.AccountType;
import com.financeos.domain.obligation.ObligationRefService;
import com.financeos.domain.statement.Statement;
import com.financeos.domain.statement.StatementDraft;
import com.financeos.domain.statement.StatementPersistenceService;
import com.financeos.domain.statement.StatementSource;
import com.financeos.domain.statement.StatementTransaction;
import com.financeos.domain.statement.StatementTransactionRepository;
import com.financeos.domain.statement.StatementVerdict;
import com.financeos.domain.transaction.ReviewReason;
import com.financeos.domain.transaction.ReviewType;
import com.financeos.domain.transaction.Transaction;
import com.financeos.domain.transaction.TransactionRepository;
import com.financeos.domain.transaction.TransactionSource;
import com.financeos.domain.transaction.TransactionType;
import com.financeos.domain.transaction.autolink.TransactionsIngestedEvent;
import com.financeos.domain.user.User;
import com.financeos.domain.user.UserRepository;
import com.financeos.gmail.domain.GmailConnection;
import com.financeos.gmail.domain.GmailConnectionRepository;
import com.financeos.gmail.domain.GmailProcessedMessage;
import com.financeos.gmail.domain.GmailProcessedMessageRepository;
import com.financeos.gmail.domain.GmailProcessedStatus;
import com.financeos.support.UserDataCleanup;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doReturn;

/**
 * The history re-match job on real H2 rows: a dry run reports and changes nothing, an apply changes exactly what the
 * dry run reported, and a second run proposes nothing.
 */
@SpringBootTest
class HistoryRematchIntegrationTest {

    private static final LocalDate OCT1 = LocalDate.of(2026, 10, 1);
    private static final LocalDate OCT17 = LocalDate.of(2026, 10, 17);
    private static final LocalDate OCT31 = LocalDate.of(2026, 10, 31);

    /** Counts ingest events that reached an after-commit listener. */
    static final AtomicInteger INGESTED_AFTER_COMMIT = new AtomicInteger();

    @TestConfiguration
    static class AfterCommitProbe {
        @Bean
        Probe ingestProbe() {
            return new Probe();
        }
    }

    static class Probe {
        @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
        public void on(TransactionsIngestedEvent e) {
            INGESTED_AFTER_COMMIT.incrementAndGet();
        }
    }

    @Autowired private HistoryRematchService service;
    @Autowired private HistoryRematchJobs jobs;
    @Autowired private UserRepository userRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private StatementTransactionRepository statementTransactionRepository;
    @Autowired private StatementPersistenceService statementPersistenceService;
    @Autowired private GmailConnectionRepository connectionRepository;
    @Autowired private GmailProcessedMessageRepository messageRepository;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbc;
    @SpyBean private ObligationRefService obligationRefService;

    private User user;
    private Account bank;
    private GmailConnection connection;

    @BeforeEach
    void setUp() {
        User u = new User();
        u.setDisplayName("rematch");
        u.setEmail("rematch-" + UUID.randomUUID() + "@example.test");
        u.setPasswordHash("hash");
        user = userRepository.save(u);
        Account a = new Account("Rematch Savings", AccountType.bank_account);
        a.setUser(user);
        a.setIngestFromDate(LocalDate.of(2026, 1, 1));
        AccountBankDetails details = new AccountBankDetails(a, BigDecimal.ZERO, "4321");
        details.setUser(user);
        a.setBankDetails(details);
        bank = accountRepository.save(a);
        GmailConnection c = new GmailConnection();
        c.setUser(user);
        c.setEmail(user.getEmail());
        c.setEncryptedRefreshToken("refresh-token");
        connection = connectionRepository.save(c);
        INGESTED_AFTER_COMMIT.set(0);
    }

    @AfterEach
    void tearDown() {
        UserDataCleanup.deleteUsers(jdbc, List.of(user.getId()));
    }

    // ------------------------------------------------------------------ merges

    @Test
    void alertAndLineOnlyRowWithDifferentTextAreMergedOnApplyAndNothingOnDryRun() {
        Transaction alert = txn(TransactionSource.gmail_transaction_alert, OCT17, "60182.88", "LIFE INSURA", null,
                EnumSet.of(ReviewReason.UNRECONCILED));
        UUID mail = mail(alert, GmailProcessedStatus.CREATED, null);
        Statement s = statement("oct");
        Transaction line = txn(TransactionSource.gmail_statement, OCT17, "60182.88", "10073240 F IN. INST", "stmt-oct:0", Set.of());
        link(s, line);

        HistoryRematchService.AccountReport dry = service.runAccount(user.getId(), bank.getId(), true);

        assertThat(dry.error()).isNull();
        assertThat(dry.counts().get("merged")).isEqualTo(1);
        assertThat(dry.merges()).hasSize(1);
        HistoryRematchService.MergeItem m = dry.merges().get(0);
        assertThat(m.keep().id()).isEqualTo(alert.getId().toString());
        assertThat(m.keep().description()).isEqualTo("LIFE INSURA");
        assertThat(m.keep().source()).isEqualTo("gmail_transaction_alert");
        assertThat(m.absorb().id()).isEqualTo(line.getId().toString());
        assertThat(m.absorb().description()).isEqualTo("10073240 F IN. INST");
        assertThat(m.statement().id()).isEqualTo(s.getId().toString());
        // nothing written
        assertThat(transactionRepository.findById(line.getId())).isPresent();
        assertThat(lineTxn(s, 0)).isEqualTo(line.getId());
        assertThat(reasons(alert.getId())).containsExactly(ReviewReason.UNRECONCILED);

        HistoryRematchService.AccountReport applied = service.runAccount(user.getId(), bank.getId(), false);

        assertThat(applied.counts()).isEqualTo(dry.counts());
        assertThat(transactionRepository.findById(line.getId())).isEmpty();
        assertThat(lineTxn(s, 0)).isEqualTo(alert.getId());
        assertThat(statementTransactionRepository.findByIdStatementId(s.getId())).hasSize(1);
        Transaction kept = transactionRepository.findById(alert.getId()).orElseThrow();
        assertThat(kept.getSourcedDescription()).isEqualTo("10073240 F IN. INST");
        assertThat(reasons(alert.getId())).isEmpty();
        assertThat(mailTxn(mail)).isEqualTo(alert.getId());

        HistoryRematchService.AccountReport again = service.runAccount(user.getId(), bank.getId(), true);
        assertThat(again.isEmpty()).as("a second run proposes nothing: %s", again.counts()).isTrue();
    }

    @Test
    void identicalLinesOfOneStatementStayApart() {
        Statement s = statement("jio");
        List<Transaction> rows = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            rows.add(txn(TransactionSource.file_upload, OCT17, "394.00", "JIO PREPAID RECHARGE", null, Set.of()));
        }
        link(s, rows.toArray(new Transaction[0]));

        HistoryRematchService.AccountReport r = service.runAccount(user.getId(), bank.getId(), false);

        assertThat(r.counts().get("merged")).isZero();
        assertThat(r.isEmpty()).isTrue();
        for (Transaction t : rows) {
            assertThat(transactionRepository.findById(t.getId())).isPresent();
        }
        assertThat(statementTransactionRepository.findByIdStatementId(s.getId())).hasSize(5);
    }

    @Test
    void refusedMergeIsReportedAndBothRowsStay() {
        Transaction alert = txn(TransactionSource.gmail_transaction_alert, OCT17, "5000.00", "EMI HDFC", null, Set.of());
        Statement s = statement("refused");
        Transaction line = txn(TransactionSource.file_upload, OCT17, "5000.00", "ACH D- HDFC BANK LTD", null, Set.of());
        link(s, line);
        UUID lineId = line.getId();
        doReturn("Both transactions are linked to loan/lending records (A and B); unlink one before merging.")
                .when(obligationRefService).checkMergeCompatibility(any(), argThat(t -> t != null && lineId.equals(t.getId())));

        HistoryRematchService.AccountReport r = service.runAccount(user.getId(), bank.getId(), false);

        assertThat(r.counts().get("merged")).isZero();
        assertThat(r.counts().get("refusedMerges")).isEqualTo(1);
        HistoryRematchService.RefusedMerge refused = r.refusedMerges().get(0);
        assertThat(refused.keep().id()).isEqualTo(alert.getId().toString());
        assertThat(refused.absorb().id()).isEqualTo(lineId.toString());
        assertThat(refused.reason()).contains("unlink one before merging");
        assertThat(transactionRepository.findById(alert.getId())).isPresent();
        assertThat(transactionRepository.findById(lineId)).isPresent();
        assertThat(lineTxn(s, 0)).isEqualTo(lineId);
        assertThat(transactionRepository.findById(alert.getId()).orElseThrow().getSourcedDescription()).isEqualTo("EMI HDFC");
    }

    // ------------------------------------------------------------------ legacy reasons

    @Test
    void legacyReasonsAreClearedAndAManuallyReviewedRowKeepsItsType() {
        Transaction dateCorrected = txn(TransactionSource.gmail_transaction_alert, OCT1, "10.00", "A", null,
                EnumSet.of(ReviewReason.DATE_CORRECTED));
        Transaction manual = txn(TransactionSource.manual, OCT1, "20.00", "B", null, Set.of());
        jdbc.update("INSERT INTO transaction_review_reasons (transaction_id, reason) VALUES (?, ?)",
                manual.getId().toString(), "UNRECONCILED");
        jdbc.update("UPDATE transactions SET review_type = 'MANUALLY_REVIEWED' WHERE id = ?", manual.getId().toString());
        Transaction stillTagged = txn(TransactionSource.gmail_transaction_alert, OCT1, "30.00", "C", null,
                EnumSet.of(ReviewReason.UNRECONCILED, ReviewReason.NOT_ON_STATEMENT));

        HistoryRematchService.AccountReport r = service.runAccount(user.getId(), bank.getId(), false);

        assertThat(r.reasonsCleared()).containsEntry("DATE_CORRECTED", 1).containsEntry("UNRECONCILED", 2);
        assertThat(r.counts().get("reviewTypeChanged")).isEqualTo(1);
        assertThat(r.reviewTypeChanges()).extracting(HistoryRematchService.ReviewChange::id)
                .containsExactly(dateCorrected.getId().toString());
        assertThat(reviewType(dateCorrected.getId())).isEqualTo(ReviewType.AUTO_REVIEWED);
        assertThat(reasons(dateCorrected.getId())).isEmpty();
        assertThat(reviewType(manual.getId())).isEqualTo(ReviewType.MANUALLY_REVIEWED);
        assertThat(reasons(manual.getId())).isEmpty();
        assertThat(reviewType(stillTagged.getId())).isEqualTo(ReviewType.NEEDS_REVIEW);
        assertThat(reasons(stillTagged.getId())).containsExactly(ReviewReason.NOT_ON_STATEMENT);
    }

    // ------------------------------------------------------------------ foreign

    @Test
    void foreignAlertStoredAsRupeesIsConvertedFromItsStatementLineAndThenFilled() {
        LocalDate oct10 = LocalDate.of(2026, 10, 10);
        Transaction alert = txn(TransactionSource.gmail_transaction_alert, oct10, "20.00", "AMAZON WEB SERVICES", null, Set.of());
        Statement s = statement("usd");
        Transaction line = txn(TransactionSource.file_upload, oct10.plusDays(2), "1712.40",
                "AMAZON WEB SERVICES SEATTLE USD 20.00", null, Set.of());
        link(s, line);

        HistoryRematchService.AccountReport dry = service.runAccount(user.getId(), bank.getId(), true);

        assertThat(dry.counts().get("foreignConverted")).isEqualTo(1);
        HistoryRematchService.ForeignItem f = dry.foreignConversions().get(0);
        assertThat(f.id()).isEqualTo(alert.getId().toString());
        assertThat(f.currency()).isEqualTo("USD");
        assertThat(f.originalAmount()).isEqualTo("20");
        assertThat(f.via()).isEqualTo("statementLine");
        assertThat(dry.counts().get("merged")).isEqualTo(1);
        assertThat(INGESTED_AFTER_COMMIT.get()).as("no after-commit listener runs on a dry run").isZero();
        Transaction untouched = transactionRepository.findById(alert.getId()).orElseThrow();
        assertThat(untouched.getAmount()).isEqualByComparingTo("20.00");
        assertThat(untouched.getOriginalCurrency()).isNull();

        service.runAccount(user.getId(), bank.getId(), false);

        Transaction t = transactionRepository.findById(alert.getId()).orElseThrow();
        assertThat(t.getOriginalCurrency()).isEqualTo("USD");
        assertThat(t.getOriginalAmount()).isEqualByComparingTo("20.00");
        assertThat(t.getAmount()).as("the statement line fills the rupee amount").isEqualByComparingTo("1712.40");
        assertThat(transactionRepository.findById(line.getId())).isEmpty();
        assertThat(INGESTED_AFTER_COMMIT.get()).as("the filled charge's event fires after the commit").isPositive();
        assertThat(service.runAccount(user.getId(), bank.getId(), true).isEmpty()).isTrue();
    }

    // ------------------------------------------------------------------ re-queue

    @Test
    @SuppressWarnings("unchecked")
    void coveredSkipIsRequeuedWithACleanSlate() throws Exception {
        statement("cover");
        UUID covered = skippedMail("Covered by statement 01/10/2026–31/10/2026");
        UUID other = skippedMail("Before the account's ingest date");

        HistoryRematchService.AccountReport dry = service.runAccount(user.getId(), bank.getId(), true);
        assertThat(dry.counts().get("requeued")).isEqualTo(1);
        assertThat(dry.requeued()).extracting(HistoryRematchService.RequeueItem::id).containsExactly(covered.toString());
        assertThat(messageRepository.findById(covered).orElseThrow().getStatus())
                .isEqualTo(GmailProcessedStatus.SKIPPED_BEFORE_WATERMARK);

        // the apply through the background job, as the endpoint starts it
        HistoryRematchJobs.Job job = jobs.start(false, user.getId(), bank.getId());
        assertThat(job).isNotNull();
        long until = System.currentTimeMillis() + 60_000;
        while ("RUNNING".equals(job.status()) && System.currentTimeMillis() < until) {
            Thread.sleep(50);
        }
        Map<String, Object> view = job.view(true);
        assertThat(view.get("status")).isEqualTo("DONE");
        assertThat(view.get("dryRun")).isEqualTo(false);
        assertThat((Map<String, Integer>) view.get("totals")).containsEntry("requeued", 1);
        GmailProcessedMessage g = messageRepository.findById(covered).orElseThrow();
        assertThat(g.getStatus()).isEqualTo(GmailProcessedStatus.DISCOVERED);
        assertThat(g.getError()).isNull();
        assertThat(g.getErrorDetail()).isNull();
        assertThat(g.getAttemptCount()).isZero();
        assertThat(g.getNextRetryAt()).isNull();
        assertThat(g.getArchivedAt()).isNull();
        assertThat(messageRepository.findById(other).orElseThrow().getStatus())
                .isEqualTo(GmailProcessedStatus.SKIPPED_BEFORE_WATERMARK);
    }

    // ------------------------------------------------------------------ helpers

    private Transaction txn(TransactionSource source, LocalDate date, String amount, String text, String messageId,
                            Set<ReviewReason> reasons) {
        Transaction t = new Transaction();
        t.setUser(user);
        t.setAccount(bank);
        t.setDate(date);
        t.setAmount(new BigDecimal(amount));
        t.setType(TransactionType.DEBIT);
        t.setSource(source);
        t.setSourceMessageId(messageId);
        t.setSourcedDescription(text);
        t.setTransactionUnderMonitoring(false);
        t.setTransactionExcluded(false);
        if (reasons.isEmpty()) {
            t.setReviewType(ReviewType.AUTO_REVIEWED);
        } else {
            t.getReviewReasons().addAll(reasons);
            t.setReviewType(ReviewType.NEEDS_REVIEW);
        }
        return transactionRepository.save(t);
    }

    private Statement statement(String ref) {
        StatementDraft draft = new StatementDraft("bank_account", OCT1, OCT31, BigDecimal.ZERO, null, "HDFC", "xxxx4321",
                1, 0, BigDecimal.ZERO, BigDecimal.ZERO, "balance_chain", new BigDecimal("100.0"), true,
                StatementVerdict.AUTO_INGEST, Map.of());
        return statementPersistenceService.createIfNew(user, bank, StatementSource.file_upload, ref, "sha-" + ref, draft)
                .orElseThrow();
    }

    private void link(Statement s, Transaction... rows) {
        List<StatementPersistenceService.TxnLink> links = new ArrayList<>();
        for (int i = 0; i < rows.length; i++) {
            links.add(new StatementPersistenceService.TxnLink(rows[i].getId(), i, null, null));
        }
        statementPersistenceService.linkTransactions(s.getId(), links);
    }

    private UUID mail(Transaction t, GmailProcessedStatus status, String error) {
        GmailProcessedMessage m = new GmailProcessedMessage();
        m.setConnection(connection);
        m.setUser(user);
        m.setAccount(bank);
        m.setGmailMessageId("msg-" + UUID.randomUUID());
        m.setStatus(status);
        m.setError(error);
        m.setTransaction(t);
        return messageRepository.save(m).getId();
    }

    private UUID skippedMail(String error) {
        GmailProcessedMessage m = new GmailProcessedMessage();
        m.setConnection(connection);
        m.setUser(user);
        m.setAccount(bank);
        m.setGmailMessageId("msg-" + UUID.randomUUID());
        m.setStatus(GmailProcessedStatus.SKIPPED_BEFORE_WATERMARK);
        m.setError(error);
        m.setErrorDetail("detail");
        m.setAttemptCount(2);
        m.setNextRetryAt(Instant.now());
        m.setArchivedAt(Instant.now());
        m.setPipelineKind("ALERT");
        m.setSubject("Alert: you spent");
        m.setInternalDate(Instant.parse("2026-10-17T05:00:00Z"));
        return messageRepository.save(m).getId();
    }

    private UUID lineTxn(Statement s, int lineIndex) {
        return statementTransactionRepository.findByIdStatementId(s.getId()).stream()
                .filter(st -> st.getLineIndex() != null && st.getLineIndex() == lineIndex)
                .map(StatementTransaction::getId).map(id -> id.getTransactionId()).findFirst().orElse(null);
    }

    private Set<ReviewReason> reasons(UUID id) {
        return new TransactionTemplate(transactionManager).execute(s ->
                Set.copyOf(transactionRepository.findById(id).orElseThrow().getReviewReasons()));
    }

    private ReviewType reviewType(UUID id) {
        return transactionRepository.findById(id).orElseThrow().getReviewType();
    }

    private UUID mailTxn(UUID mailId) {
        return new TransactionTemplate(transactionManager).execute(s -> {
            Transaction t = messageRepository.findById(mailId).orElseThrow().getTransaction();
            return t == null ? null : t.getId();
        });
    }
}
