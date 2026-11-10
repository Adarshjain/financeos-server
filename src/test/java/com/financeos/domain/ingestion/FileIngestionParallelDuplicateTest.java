package com.financeos.domain.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.financeos.core.security.UserContext;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.account.AccountService;
import com.financeos.domain.account.AccountType;
import com.financeos.domain.categorization.CategorizationService;
import com.financeos.domain.statement.StatementDraft;
import com.financeos.domain.statement.StatementPersistenceService;
import com.financeos.domain.statement.StatementVerdict;
import com.financeos.domain.statement.file.StatementFileAttacher;
import com.financeos.domain.transaction.ReviewStatusManager;
import com.financeos.domain.transaction.TransactionMatcher;
import com.financeos.domain.transaction.match.StatementBatchMatcher;
import com.financeos.domain.user.User;
import com.financeos.domain.user.UserRepository;
import com.financeos.gmail.reconcile.ParsedStatementLine;
import com.financeos.gmail.reconcile.StatementExtractionResult;
import com.financeos.gmail.reconcile.StatementParser;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Two uploads of the same statement running in parallel both pass the existence checks and the second insert hits the
 * unique key: that file is SKIPPED like any duplicate, never FAILED with the database's error text.
 */
class FileIngestionParallelDuplicateTest {

    private static final String SKIPPED_MESSAGE = "Statement already ingested (same period or file)";
    private static final String FIXED_FAILURE = "Something went wrong while saving this statement. Please try again.";
    private static final String ORA = "could not execute statement [ORA-00001: unique constraint (FINANCEOS.UK_STATEMENTS_ACCOUNT_PERIOD) violated]";

    private final StatementParser parser = mock(StatementParser.class);
    private final StatementPersistenceService persistence = mock(StatementPersistenceService.class);
    private final StatementBatchMatcher batchMatcher = mock(StatementBatchMatcher.class);
    private final StatementFileAttacher attacher = mock(StatementFileAttacher.class);
    private FileIngestionService service;
    private UUID accountId;
    private byte[] bytes;

    @BeforeEach
    void setUp() {
        AccountService accountService = mock(AccountService.class);
        UserRepository userRepository = mock(UserRepository.class);
        service = new FileIngestionService(mock(AccountRepository.class), userRepository, parser,
                mock(FileIngestionDbHandler.class), mock(TransactionMatcher.class), mock(ReviewStatusManager.class),
                mock(CategorizationService.class), persistence, accountService);
        service.setBatchMatcher(batchMatcher);
        service.setFileAttacher(attacher);
        UUID userId = UUID.randomUUID();
        accountId = UUID.randomUUID();
        UserContext.setCurrentUserId(userId);
        User user = new User();
        user.setId(userId);
        Account account = new Account();
        account.setId(accountId);
        account.setUser(user);
        account.setType(AccountType.credit_card);
        when(accountService.getAccountById(accountId)).thenReturn(account);
        when(userRepository.getReferenceById(userId)).thenReturn(user);
        StatementDraft draft = new StatementDraft("bank_account", LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30), null, null,
                "Bank", "1234", 1, 0, BigDecimal.TEN, BigDecimal.ZERO, "heuristic", null, null, StatementVerdict.AUTO_INGEST, null, null);
        bytes = "dup".getBytes();
        ParsedStatementLine line = new ParsedStatementLine(LocalDate.of(2026, 9, 5), new BigDecimal("10"), "DEBIT", "Txn", null, null);
        when(parser.parse(eq(bytes), nullable(String.class))).thenReturn(StatementExtractionResult.success(
                List.of(line), "1234", LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30), draft));
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private FileIngestionResult ingest() {
        return service.ingest(accountId, List.of(new UploadedFile("s.pdf", "application/pdf", bytes)));
    }

    private void createFailsWith(RuntimeException e) {
        when(persistence.createIfNew(any(), any(), any(), any(), any(), nullable(StatementDraft.class))).thenThrow(e);
    }

    private void assertSkippedAsDuplicate(FileIngestionResult res) {
        FileIngestionResult.FileSummary s = res.fileDetails().get(0);
        assertThat(s.status()).isEqualTo("SKIPPED");
        assertThat(s.errorMessage()).isEqualTo(SKIPPED_MESSAGE);
        assertThat(res.filesProcessed()).isEqualTo(1);
        assertThat(res.totalCreated()).isZero();
        verify(batchMatcher, never()).match(any(), any(), any(), any());
        verify(attacher).attachIfStoredStatementLacksFile(any(), eq(accountId), any(), eq(bytes), eq("s.pdf"));
    }

    @Test
    void aUniqueKeyFailureWhenTheStatementNowExistsByPeriodIsSkippedAsDuplicate() {
        createFailsWith(new DataIntegrityViolationException(ORA, new SQLException(ORA, "23000", 1)));
        when(persistence.alreadyExists(eq(accountId), any(), any())).thenReturn(true);

        assertSkippedAsDuplicate(ingest());
    }

    @Test
    void aUniqueKeyFailureWhenTheStatementNowExistsByFileHashIsSkippedAsDuplicate() {
        createFailsWith(new DataIntegrityViolationException("ORA-00001: unique constraint (FINANCEOS.UK_STMT_ACCOUNT_SHA256) violated"));
        when(persistence.alreadyExists(eq(accountId), any(), eq(StatementPersistenceService.sha256Hex(bytes)))).thenReturn(true);

        assertSkippedAsDuplicate(ingest());
    }

    @Test
    void anIntegrityViolationWithNoMatchingStatementFailsWithTheFixedSentenceNotTheDatabaseText() {
        createFailsWith(new DataIntegrityViolationException(ORA));
        when(persistence.alreadyExists(any(), any(), any())).thenReturn(false);

        FileIngestionResult res = ingest();

        FileIngestionResult.FileSummary s = res.fileDetails().get(0);
        assertThat(s.status()).isEqualTo("FAILED");
        assertThat(s.errorMessage()).isEqualTo(FIXED_FAILURE).doesNotContain("ORA-");
        assertThat(s.errorCode()).isEqualTo(FileIngestionResult.ErrorCode.OTHER);
        assertThat(res.filesProcessed()).isZero();
        verify(attacher, never()).attachIfStoredStatementLacksFile(any(), any(), any(), any(), any());
    }

    @Test
    void anyOtherDataAccessExceptionFailsWithTheFixedSentence() {
        createFailsWith(new DataAccessResourceFailureException("ORA-12170: connect timeout"));

        FileIngestionResult.FileSummary s = ingest().fileDetails().get(0);

        assertThat(s.status()).isEqualTo("FAILED");
        assertThat(s.errorMessage()).isEqualTo(FIXED_FAILURE);
        assertThat(s.errorCode()).isEqualTo(FileIngestionResult.ErrorCode.OTHER);
        verify(persistence, never()).alreadyExists(any(), any(), any());
    }

    @Test
    void aNonDatabaseFailureKeepsItsOwnMessage() {
        createFailsWith(new IllegalStateException("boom"));

        FileIngestionResult.FileSummary s = ingest().fileDetails().get(0);

        assertThat(s.status()).isEqualTo("FAILED");
        assertThat(s.errorMessage()).isEqualTo("boom");
    }
}
