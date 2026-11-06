package com.financeos.gmail.ingest;

import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.account.AccountType;
import com.financeos.domain.statement.StatementCoverage;
import com.financeos.domain.statement.StatementRepository;
import com.financeos.domain.statement.StatementTransaction;
import com.financeos.domain.statement.StatementTransactionRepository;
import com.financeos.domain.statement.StatementVerdict;
import com.financeos.domain.statement.skip.StatementSkippedPeriodRepository;
import com.financeos.domain.transaction.ReviewReason;
import com.financeos.domain.transaction.ReviewStatusManager;
import com.financeos.domain.transaction.ReviewType;
import com.financeos.domain.transaction.Transaction;
import com.financeos.domain.transaction.TransactionRepository;
import com.financeos.domain.transaction.TransactionSource;
import com.financeos.domain.transaction.TransactionType;
import com.financeos.domain.transaction.match.AlertMatcher;
import com.financeos.domain.transaction.match.MatchWindow;
import com.financeos.domain.user.User;
import com.financeos.gmail.domain.GmailConnection;
import com.financeos.gmail.domain.GmailProcessedMessage;
import com.financeos.gmail.domain.GmailProcessedMessageRepository;
import com.financeos.gmail.domain.GmailProcessedStatus;
import com.financeos.gmail.ingest.gemini.GeminiExtractionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * The writer matches every alert through the alert matcher against statement periods: a covered alert pairs with its
 * statement's line or is created tagged not-on-statement; an uncovered one (gap, after the latest statement, a
 * "no statement this period" range) is created Pending.
 */
class GmailTransactionWriterStatementCoverageTest {

    private TransactionRepository transactionRepository;
    private StatementRepository statementRepository;
    private StatementSkippedPeriodRepository skipRepository;
    private StatementTransactionRepository statementTransactionRepository;
    private GmailTransactionWriter writer;
    private GmailConnection connection;
    private Account account;
    private final UUID march = UUID.randomUUID();
    private final UUID may = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        transactionRepository = mock(TransactionRepository.class);
        GmailProcessedMessageRepository processed = mock(GmailProcessedMessageRepository.class);
        statementRepository = mock(StatementRepository.class);
        skipRepository = mock(StatementSkippedPeriodRepository.class);
        statementTransactionRepository = mock(StatementTransactionRepository.class);
        writer = new GmailTransactionWriter(transactionRepository, processed, new ReviewStatusManager());
        AlertMatcher matcher = new AlertMatcher(mock(AccountRepository.class), transactionRepository, statementTransactionRepository,
                processed, new StatementCoverage(statementRepository, skipRepository), new ReviewStatusManager(), new MatchWindow());
        ReflectionTestUtils.setField(writer, "alertMatcher", matcher);
        User user = new User();
        user.setId(UUID.randomUUID());
        connection = new GmailConnection();
        connection.setId(UUID.randomUUID());
        connection.setUser(user);
        account = new Account();
        account.setId(UUID.randomUUID());
        account.setType(AccountType.credit_card);
        account.setIngestFromDate(LocalDate.of(2026, 1, 1));
        when(processed.findByConnectionIdAndGmailMessageId(any(), any())).thenReturn(Optional.empty());
        when(processed.save(any())).thenAnswer(i -> i.getArgument(0));
        when(transactionRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(statementRepository.findCoveragePeriodsByAccountIdIn(any())).thenReturn(List.of());
        when(skipRepository.findRangesByAccountIds(any())).thenReturn(List.of());
    }

    private static GeminiExtractionResult alert(LocalDate date) {
        return new GeminiExtractionResult(true, new BigDecimal("450.00"), "INR", "DEBIT", date, "SWIGGY", "7106", 0.9, false, null);
    }

    private StatementRepository.StatementCoveragePeriod period(UUID id, LocalDate start, LocalDate end) {
        StatementRepository.StatementCoveragePeriod p = mock(StatementRepository.StatementCoveragePeriod.class);
        when(p.getId()).thenReturn(id);
        when(p.getAccountId()).thenReturn(account.getId());
        when(p.getPeriodStart()).thenReturn(start);
        when(p.getPeriodEnd()).thenReturn(end);
        when(p.getVerdict()).thenReturn(StatementVerdict.AUTO_INGEST);
        return p;
    }

    private StatementSkippedPeriodRepository.SkippedRange skip(LocalDate start, LocalDate end) {
        StatementSkippedPeriodRepository.SkippedRange s = mock(StatementSkippedPeriodRepository.SkippedRange.class);
        when(s.getAccountId()).thenReturn(account.getId());
        when(s.getPeriodStart()).thenReturn(start);
        when(s.getPeriodEnd()).thenReturn(end);
        return s;
    }

    private void twoStatementsWithGap() {
        var m = period(march, LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31));
        var y = period(may, LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 31));
        when(statementRepository.findCoveragePeriodsByAccountIdIn(any())).thenReturn(List.of(m, y));
    }

    private Transaction createdTransaction(GmailProcessedMessage r) {
        assertThat(r.getStatus()).isEqualTo(GmailProcessedStatus.CREATED);
        return r.getTransaction();
    }

    @Test
    void coveredWithNoLineIsCreatedTaggedNotOnItsStatement() {
        twoStatementsWithGap();
        Transaction t = createdTransaction(writer.writeTransaction(connection, "m1", alert(LocalDate.of(2026, 3, 15)), account));
        assertThat(t.getReviewReasons()).containsExactly(ReviewReason.NOT_ON_STATEMENT);
        assertThat(t.getNotOnStatementId()).isEqualTo(march);
    }

    @Test
    void coveredWithALineAttachesToItAndCreatesNothing() {
        twoStatementsWithGap();
        Transaction line = new Transaction();
        line.setId(UUID.randomUUID());
        line.setAccount(account);
        line.setDate(LocalDate.of(2026, 3, 16));
        line.setAmount(new BigDecimal("450.00"));
        line.setType(TransactionType.DEBIT);
        line.setSource(TransactionSource.file_upload);
        line.setSourcedDescription("SWIGGY BANGALORE");
        when(transactionRepository.findLedgerByAccountIdAndDateRange(eq(account.getId()), any(), any())).thenReturn(List.of(line));
        when(statementTransactionRepository.findLinksForAccountInRange(eq(account.getId()), any(), any()))
                .thenReturn(List.of(new StatementTransaction(march, line.getId(), 4, null, null)));

        GmailProcessedMessage r = writer.writeTransaction(connection, "m1", alert(LocalDate.of(2026, 3, 15)), account);

        assertThat(r.getStatus()).isEqualTo(GmailProcessedStatus.SKIPPED_DUPLICATE);
        assertThat(r.getTransaction()).isSameAs(line);
        assertThat(r.getError()).isEqualTo("Attached to an existing transaction (statement line 16/03/2026)");
        verify(transactionRepository, never()).save(any());
    }

    @Test
    void gapBetweenStatementsIsCreatedPending() {
        twoStatementsWithGap();
        Transaction t = createdTransaction(writer.writeTransaction(connection, "m1", alert(LocalDate.of(2026, 4, 15)), account));
        assertThat(t.getReviewReasons()).isEmpty();
        assertThat(t.getReviewType()).isEqualTo(ReviewType.AUTO_REVIEWED);
    }

    @Test
    void afterLatestPeriodEndIsCreatedPending() {
        twoStatementsWithGap();
        Transaction t = createdTransaction(writer.writeTransaction(connection, "m1", alert(LocalDate.of(2026, 6, 2)), account));
        assertThat(t.getReviewReasons()).isEmpty();
    }

    @Test
    void accountWithoutStatementsIsCreatedPending() {
        Transaction t = createdTransaction(writer.writeTransaction(connection, "m1", alert(LocalDate.of(2026, 3, 15)), account));
        assertThat(t.getReviewReasons()).isEmpty();
    }

    @Test
    void insideANoStatementPeriodIsNotCoveredAndIsCreatedPending() {
        twoStatementsWithGap();
        var april = skip(LocalDate.of(2026, 4, 1), LocalDate.of(2026, 4, 30));
        when(skipRepository.findRangesByAccountIds(any())).thenReturn(List.of(april));
        Transaction t = createdTransaction(writer.writeTransaction(connection, "m1", alert(LocalDate.of(2026, 4, 15)), account));
        assertThat(t.getReviewReasons()).isEmpty();
        assertThat(t.getNotOnStatementId()).isNull();
    }

    @Test
    void bankAccountCoveredWithNoLineIsTagged() {
        account.setType(AccountType.bank_account);
        twoStatementsWithGap();
        Transaction t = createdTransaction(writer.writeTransaction(connection, "m1", alert(LocalDate.of(2026, 5, 10)), account));
        assertThat(t.getNotOnStatementId()).isEqualTo(may);
    }

    @Test
    void lookupsAreCachedPerAccount() {
        twoStatementsWithGap();
        writer.writeTransaction(connection, "m1", alert(LocalDate.of(2026, 3, 15)), account);
        writer.writeTransaction(connection, "m2", alert(LocalDate.of(2026, 3, 16)), account);
        verify(statementRepository, times(1)).findCoveragePeriodsByAccountIdIn(any());
        verify(skipRepository, times(1)).findRangesByAccountIds(any());
    }

    @Test
    void aRealProviderIssuerMergePointsTheMailAtTheTransactionAndIsCounted() {
        var registry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        ReflectionTestUtils.setField(writer, "observabilityMetrics", new com.financeos.core.observability.ObservabilityMetrics(registry));
        Transaction issuerRow = new Transaction();
        issuerRow.setId(UUID.randomUUID());
        GmailProcessedMessage mail = new GmailProcessedMessage();

        writer.recordAttached(mail, account, new AlertMatcher.Attached(issuerRow, AlertMatcher.Kind.ISSUER_EMAIL,
                "Attached to an existing transaction (issuer email)"));

        assertThat(mail.getStatus()).isEqualTo(GmailProcessedStatus.SKIPPED_DUPLICATE);
        assertThat(mail.getTransaction()).isSameAs(issuerRow);
        assertThat(mail.getExtractMergeTxn()).isEqualTo(issuerRow.getId());
        assertThat(registry.find("financeos.gmail.extract.merge").tag("kind", "provider_after_issuer").counter().count()).isEqualTo(1.0);
    }
}
