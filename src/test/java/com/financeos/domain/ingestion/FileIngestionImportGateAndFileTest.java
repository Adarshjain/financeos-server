package com.financeos.domain.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.financeos.core.security.UserContext;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.account.AccountService;
import com.financeos.domain.account.AccountType;
import com.financeos.domain.categorization.CategorizationService;
import com.financeos.domain.statement.Statement;
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
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The upload path's import gate (a parse we cannot trust creates nothing) and the keeping of the original file
 * (attached after a statement is created or refreshed, back-filled on an identical re-upload).
 */
class FileIngestionImportGateAndFileTest {

    private final StatementParser parser = mock(StatementParser.class);
    private final StatementPersistenceService persistence = mock(StatementPersistenceService.class);
    private final StatementBatchMatcher batchMatcher = mock(StatementBatchMatcher.class);
    private final StatementFileAttacher attacher = mock(StatementFileAttacher.class);
    private FileIngestionService service;
    private UUID userId;
    private UUID accountId;

    @BeforeEach
    void setUp() {
        AccountService accountService = mock(AccountService.class);
        UserRepository userRepository = mock(UserRepository.class);
        service = new FileIngestionService(mock(AccountRepository.class), userRepository, parser,
                mock(FileIngestionDbHandler.class), mock(TransactionMatcher.class), mock(ReviewStatusManager.class),
                mock(CategorizationService.class), persistence, accountService);
        service.setBatchMatcher(batchMatcher);
        service.setFileAttacher(attacher);
        userId = UUID.randomUUID();
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
        when(batchMatcher.match(any(), any(), any(), any())).thenReturn(new StatementBatchMatcher.Result(0, 0, 0, 0, 0, List.of()));
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private static StatementDraft draft(StatementVerdict verdict, String mode, Boolean checksumOk) {
        return new StatementDraft("bank_account", LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30), null, null, "Bank",
                "1234", 1, 0, BigDecimal.TEN, BigDecimal.ZERO, mode, null, checksumOk, verdict, null, null);
    }

    private byte[] parsed(String content, String number, LocalDate periodEnd, StatementDraft draft) {
        byte[] bytes = content.getBytes();
        ParsedStatementLine line = new ParsedStatementLine(LocalDate.of(2026, 9, 5), new BigDecimal("10"), "DEBIT", "Txn", null, null);
        when(parser.parse(eq(bytes), nullable(String.class))).thenReturn(StatementExtractionResult.success(
                List.of(line), number, periodEnd == null ? null : periodEnd.minusDays(29), periodEnd, draft));
        return bytes;
    }

    private FileIngestionResult.FileSummary ingest(byte[] bytes) {
        return service.ingest(accountId, List.of(new UploadedFile("s.pdf", "application/pdf", bytes))).fileDetails().get(0);
    }

    private Statement created() {
        Statement s = new Statement();
        s.setId(UUID.randomUUID());
        when(persistence.createIfNew(any(), any(), any(), any(), any(), nullable(StatementDraft.class))).thenReturn(Optional.of(s));
        return s;
    }

    // ---- the gate

    @Test
    void aRejectedParseWhoseFiguresContradictItsRowsIsFailedAndCreatesNothing() {
        byte[] bytes = parsed("a", "1234", LocalDate.of(2026, 9, 30), draft(StatementVerdict.REJECTED, "opening_closing", Boolean.FALSE));

        FileIngestionResult res = service.ingest(accountId, List.of(new UploadedFile("s.pdf", "application/pdf", bytes)));

        FileIngestionResult.FileSummary s = res.fileDetails().get(0);
        assertThat(s.status()).isEqualTo("FAILED");
        assertThat(s.errorMessage()).isEqualTo("This statement's rows don't add up to its own totals, so nothing was imported.");
        assertThat(s.errorCode()).isEqualTo(FileIngestionResult.ErrorCode.OTHER);
        assertThat(res.filesProcessed()).isZero();
        assertThat(res.totalCreated()).isZero();
        verify(persistence, never()).createIfNew(any(), any(), any(), any(), any(), any());
        verify(persistence, never()).refreshReconciled(any(), any(), any(), any(), any());
        verify(persistence, never()).refreshFromReupload(any(), any(), any());
        verifyNoInteractions(batchMatcher);
        verifyNoInteractions(attacher);
    }

    @Test
    void aFileWithNoNumberAndNoPeriodIsFailedAsNotAStatement() {
        byte[] bytes = parsed("b", null, null, draft(StatementVerdict.AUTO_INGEST, "heuristic", null));

        FileIngestionResult res = service.ingest(accountId, List.of(new UploadedFile("s.pdf", "application/pdf", bytes)));

        FileIngestionResult.FileSummary s = res.fileDetails().get(0);
        assertThat(s.status()).isEqualTo("FAILED");
        assertThat(s.errorMessage()).isEqualTo("This file doesn't look like an account statement, so nothing was imported.");
        assertThat(res.filesProcessed()).isZero();
        verify(persistence, never()).createIfNew(any(), any(), any(), any(), any(), any());
        verifyNoInteractions(batchMatcher);
        verifyNoInteractions(attacher);
    }

    @Test
    void aNeedsReviewStatementWithANumberStillImports() {
        byte[] bytes = parsed("c", "1234", null, draft(StatementVerdict.NEEDS_REVIEW, "heuristic", null));
        created();

        FileIngestionResult res = service.ingest(accountId, List.of(new UploadedFile("s.pdf", "application/pdf", bytes)));

        assertThat(res.fileDetails().get(0).status()).isEqualTo("SUCCESS");
        assertThat(res.filesProcessed()).isEqualTo(1);
        verify(batchMatcher).match(any(), any(), any(), any());
    }

    @Test
    void aRejectedParseWithNothingToCheckStillImportsWithTheUnverifiedWarning() {
        byte[] bytes = parsed("d", "1234", LocalDate.of(2026, 9, 30), draft(StatementVerdict.REJECTED, "heuristic", null));
        created();

        FileIngestionResult.FileSummary s = ingest(bytes);

        assertThat(s.status()).isEqualTo("SUCCESS");
        assertThat(s.warning()).contains("couldn't be verified against the statement's balances");
        verify(batchMatcher).match(any(), any(), any(), any());
    }

    @Test
    void aRejectedCardParseWithNothingToCheckAgainstStillImportsWithTheWarning() {
        // The shape the real parser gives a card statement with no readable summary: REJECTED, no checksum, card check FALSE.
        StatementDraft card = new StatementDraft("credit_card", LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30), null, null,
                "Bank", "1234", 1, 0, BigDecimal.TEN, BigDecimal.ZERO, "heuristic", null, null, StatementVerdict.REJECTED, null, Boolean.FALSE);
        byte[] bytes = parsed("n", "1234", LocalDate.of(2026, 9, 30), card);
        created();

        FileIngestionResult.FileSummary s = ingest(bytes);

        assertThat(s.status()).isEqualTo("SUCCESS");
        assertThat(s.warning()).contains("couldn't be verified against the statement's balances");
        verify(batchMatcher).match(any(), any(), any(), any());
    }

    @Test
    void anEmptyParseKeepsTheNoTransactionsMessageAndKeepsNoFile() {
        byte[] bytes = "e".getBytes();
        when(parser.parse(eq(bytes), nullable(String.class))).thenReturn(StatementExtractionResult.success(
                List.of(), null, null, null, null));

        FileIngestionResult.FileSummary s = ingest(bytes);

        assertThat(s.errorMessage()).isEqualTo("No transactions found");
        verifyNoInteractions(attacher);
    }

    @Test
    void aParseFailureKeepsNoFile() {
        byte[] bytes = "f".getBytes();
        when(parser.parse(eq(bytes), nullable(String.class))).thenReturn(StatementExtractionResult.failure("Error: failed to read"));

        assertThat(ingest(bytes).status()).isEqualTo("FAILED");
        verifyNoInteractions(attacher);
    }

    // ---- keeping the original file

    @Test
    void aNewStatementGetsItsFileAttached() {
        byte[] bytes = parsed("g", "1234", LocalDate.of(2026, 9, 30), draft(StatementVerdict.AUTO_INGEST, "heuristic", null));
        Statement s = created();

        ingest(bytes);

        verify(attacher).attach(userId, s.getId(), bytes, "s.pdf");
    }

    @Test
    void aRefreshedStatementGetsItsFileAttached() {
        byte[] bytes = parsed("h", "1234", LocalDate.of(2026, 9, 30), draft(StatementVerdict.AUTO_INGEST, "heuristic", null));
        Statement s = refreshing(StatementPersistenceService.sha256Hex(bytes));

        ingest(bytes);

        verify(attacher).attach(userId, s.getId(), bytes, "s.pdf");
        verify(persistence, never()).createIfNew(any(), any(), any(), any(), any(), any());
    }

    @Test
    void aRefreshMatchedFromOtherBytesDoesNotAttachThisFile() {
        byte[] bytes = parsed("h2", "1234", LocalDate.of(2026, 9, 30), draft(StatementVerdict.AUTO_INGEST, "heuristic", null));
        refreshing("some-other-file-sha");

        FileIngestionResult.FileSummary s = ingest(bytes);

        assertThat(s.status()).isNotEqualTo("FAILED");
        verifyNoInteractions(attacher);
    }

    @Test
    void aRefreshOfAStatementWithNoRecordedHashDoesNotAttach() {
        byte[] bytes = parsed("h3", "1234", LocalDate.of(2026, 9, 30), draft(StatementVerdict.AUTO_INGEST, "heuristic", null));
        refreshing(null);

        ingest(bytes);

        verifyNoInteractions(attacher);
    }

    private Statement refreshing(String recordedSha) {
        Statement s = new Statement();
        s.setId(UUID.randomUUID());
        s.setFileSha256(recordedSha);
        when(persistence.refreshReconciled(any(), any(), nullable(StatementDraft.class), any(), any())).thenReturn(Optional.of(
                new StatementPersistenceService.Refreshed(s, List.of(), List.of(), List.of())));
        return s;
    }

    @Test
    void aGapFillReUploadBackFillsTheStoredStatementsFile() {
        byte[] bytes = parsed("i", "1234", LocalDate.of(2026, 9, 30), draft(StatementVerdict.AUTO_INGEST, "heuristic", null));
        when(persistence.refreshFromReupload(eq(accountId), any(), nullable(StatementDraft.class))).thenReturn(Optional.of(List.of()));

        ingest(bytes);

        verify(attacher).attachIfStoredStatementLacksFile(eq(userId), eq(accountId), eq(StatementPersistenceService.sha256Hex(bytes)),
                eq(bytes), eq("s.pdf"));
        verify(attacher, never()).attach(any(), any(), any(), any());
    }

    @Test
    void anAlreadyIngestedFileBackFillsTheStoredStatementsFile() {
        byte[] bytes = parsed("j", "1234", LocalDate.of(2026, 9, 30), draft(StatementVerdict.AUTO_INGEST, "heuristic", null));
        when(persistence.createIfNew(any(), any(), any(), any(), any(), nullable(StatementDraft.class))).thenReturn(Optional.empty());

        FileIngestionResult.FileSummary s = ingest(bytes);

        assertThat(s.errorMessage()).isEqualTo("Statement already ingested (same period or file)");
        verify(attacher).attachIfStoredStatementLacksFile(eq(userId), eq(accountId), eq(StatementPersistenceService.sha256Hex(bytes)),
                eq(bytes), eq("s.pdf"));
        verify(attacher, never()).attach(any(), any(), any(), any());
    }

    @Test
    void anAttacherThatThrowsCannotFailTheFile() {
        byte[] bytes = parsed("k", "1234", LocalDate.of(2026, 9, 30), draft(StatementVerdict.AUTO_INGEST, "heuristic", null));
        created();
        doThrow(new RuntimeException("store down")).when(attacher).attach(any(), any(), any(), any());

        FileIngestionResult res = service.ingest(accountId, List.of(new UploadedFile("s.pdf", "application/pdf", bytes)));

        assertThat(res.fileDetails().get(0).status()).isEqualTo("SUCCESS");
        assertThat(res.filesProcessed()).isEqualTo(1);
        verify(batchMatcher).match(any(), any(), any(), any());
    }

    @Test
    void aThrowingBackFillCannotFailAnAlreadyIngestedFile() {
        byte[] bytes = parsed("l", "1234", LocalDate.of(2026, 9, 30), draft(StatementVerdict.AUTO_INGEST, "heuristic", null));
        when(persistence.createIfNew(any(), any(), any(), any(), any(), nullable(StatementDraft.class))).thenReturn(Optional.empty());
        doThrow(new RuntimeException("store down")).when(attacher).attachIfStoredStatementLacksFile(any(), any(), any(), any(), any());

        assertThat(ingest(bytes).status()).isEqualTo("SKIPPED");
    }

    @Test
    void withoutAnAttacherUploadsStillWork() {
        byte[] bytes = parsed("m", "1234", LocalDate.of(2026, 9, 30), draft(StatementVerdict.AUTO_INGEST, "heuristic", null));
        created();
        service.setFileAttacher(null);

        assertThat(ingest(bytes).status()).isEqualTo("SUCCESS");
    }
}
