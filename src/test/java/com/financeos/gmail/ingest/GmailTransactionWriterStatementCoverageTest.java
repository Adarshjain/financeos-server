package com.financeos.gmail.ingest;

import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountType;
import com.financeos.domain.statement.StatementCoverage;
import com.financeos.domain.statement.StatementRepository;
import com.financeos.domain.statement.StatementVerdict;
import com.financeos.domain.statement.skip.StatementSkippedPeriodRepository;
import com.financeos.domain.transaction.ReviewStatusManager;
import com.financeos.domain.transaction.TransactionRepository;
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

/** Statements are the source of truth: an alert dated inside a covered period writes no transaction. */
class GmailTransactionWriterStatementCoverageTest {

    private TransactionRepository transactionRepository;
    private StatementRepository statementRepository;
    private StatementSkippedPeriodRepository skipRepository;
    private GmailTransactionWriter writer;
    private GmailConnection connection;
    private Account account;

    @BeforeEach
    void setUp() {
        transactionRepository = mock(TransactionRepository.class);
        GmailProcessedMessageRepository processed = mock(GmailProcessedMessageRepository.class);
        statementRepository = mock(StatementRepository.class);
        skipRepository = mock(StatementSkippedPeriodRepository.class);
        writer = new GmailTransactionWriter(transactionRepository, processed, new ReviewStatusManager());
        ReflectionTestUtils.setField(writer, "statementCoverage", new StatementCoverage(statementRepository, skipRepository));
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
        when(statementRepository.findPeriodsByAccountIdIn(any())).thenReturn(List.of());
        when(skipRepository.findRangesByAccountIds(any())).thenReturn(List.of());
    }

    private static GeminiExtractionResult alert(LocalDate date) {
        return new GeminiExtractionResult(true, new BigDecimal("450.00"), "INR", "DEBIT", date, "SWIGGY", "7106", 0.9, false, null);
    }

    private StatementRepository.StatementPeriod period(LocalDate start, LocalDate end) {
        StatementRepository.StatementPeriod p = mock(StatementRepository.StatementPeriod.class);
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
        var march = period(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31));
        var may = period(LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 31));
        when(statementRepository.findPeriodsByAccountIdIn(any())).thenReturn(List.of(march, may));
    }

    @Test
    void coveredByStatementCreatesNoTransaction() {
        twoStatementsWithGap();
        GmailProcessedMessage r = writer.writeTransaction(connection, "m1", alert(LocalDate.of(2026, 3, 15)), account);
        assertThat(r.getStatus()).isEqualTo(GmailProcessedStatus.SKIPPED_BEFORE_WATERMARK);
        assertThat(r.getTransaction()).isNull();
        assertThat(r.getError()).isEqualTo("Covered by statement 01/03/2026–31/03/2026");
        verify(transactionRepository, never()).save(any());
    }

    @Test
    void gapBetweenStatementsCreatesTransaction() {
        twoStatementsWithGap();
        GmailProcessedMessage r = writer.writeTransaction(connection, "m1", alert(LocalDate.of(2026, 4, 15)), account);
        assertThat(r.getStatus()).isEqualTo(GmailProcessedStatus.CREATED);
    }

    @Test
    void afterLatestPeriodEndCreatesTransaction() {
        twoStatementsWithGap();
        GmailProcessedMessage r = writer.writeTransaction(connection, "m1", alert(LocalDate.of(2026, 6, 2)), account);
        assertThat(r.getStatus()).isEqualTo(GmailProcessedStatus.CREATED);
    }

    @Test
    void accountWithoutStatementsCreatesTransaction() {
        GmailProcessedMessage r = writer.writeTransaction(connection, "m1", alert(LocalDate.of(2026, 3, 15)), account);
        assertThat(r.getStatus()).isEqualTo(GmailProcessedStatus.CREATED);
    }

    @Test
    void insideNoStatementPeriodIsSkippedWhenNotAfterLatestStatement() {
        twoStatementsWithGap();
        var april = skip(LocalDate.of(2026, 4, 1), LocalDate.of(2026, 4, 30));
        when(skipRepository.findRangesByAccountIds(any())).thenReturn(List.of(april));
        GmailProcessedMessage r = writer.writeTransaction(connection, "m1", alert(LocalDate.of(2026, 4, 15)), account);
        assertThat(r.getStatus()).isEqualTo(GmailProcessedStatus.SKIPPED_BEFORE_WATERMARK);
        assertThat(r.getError()).isEqualTo("Covered by a no-statement period 01/04/2026–30/04/2026");
        verify(transactionRepository, never()).save(any());
    }

    @Test
    void noStatementPeriodAfterLatestStatementDoesNotCover() {
        twoStatementsWithGap();
        var june = skip(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30));
        when(skipRepository.findRangesByAccountIds(any())).thenReturn(List.of(june));
        GmailProcessedMessage r = writer.writeTransaction(connection, "m1", alert(LocalDate.of(2026, 6, 15)), account);
        assertThat(r.getStatus()).isEqualTo(GmailProcessedStatus.CREATED);
    }

    @Test
    void bankAccountCoveredIsSkipped() {
        account.setType(AccountType.bank_account);
        twoStatementsWithGap();
        GmailProcessedMessage r = writer.writeTransaction(connection, "m1", alert(LocalDate.of(2026, 5, 10)), account);
        assertThat(r.getStatus()).isEqualTo(GmailProcessedStatus.SKIPPED_BEFORE_WATERMARK);
        verify(transactionRepository, never()).save(any());
    }

    @Test
    void lookupsAreCachedPerAccount() {
        twoStatementsWithGap();
        writer.writeTransaction(connection, "m1", alert(LocalDate.of(2026, 3, 15)), account);
        writer.writeTransaction(connection, "m2", alert(LocalDate.of(2026, 3, 16)), account);
        verify(statementRepository, times(1)).findPeriodsByAccountIdIn(any());
        verify(skipRepository, times(1)).findRangesByAccountIds(any());
    }
}
