package com.financeos.domain.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doAnswer;
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
import com.financeos.domain.statement.Statement;
import com.financeos.domain.statement.StatementDraft;
import com.financeos.domain.statement.StatementPersistenceService;
import com.financeos.domain.statement.StatementTransaction;
import com.financeos.domain.transaction.ReviewStatusManager;
import com.financeos.domain.transaction.Transaction;
import com.financeos.domain.transaction.TransactionMatcher;
import com.financeos.domain.transaction.TransactionSource;
import com.financeos.domain.transaction.TransactionType;
import com.financeos.domain.transaction.match.MatchFixture;
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

/** A reconciled re-upload of an existing statement: only its missing lines go through matching; the summary says what changed. */
class FileIngestionRefreshTest {

    private final StatementParser parser = mock(StatementParser.class);
    private final StatementPersistenceService persistence = mock(StatementPersistenceService.class);
    private final FileIngestionDbHandler dbHandler = mock(FileIngestionDbHandler.class);
    private final MatchFixture fixture = new MatchFixture();
    private FileIngestionService service;
    private UUID accountId;
    private Account account;
    private Statement statement;

    @BeforeEach
    void setUp() {
        AccountService accountService = mock(AccountService.class);
        UserRepository userRepository = mock(UserRepository.class);
        service = new FileIngestionService(mock(AccountRepository.class), userRepository, parser, dbHandler,
                mock(TransactionMatcher.class), mock(ReviewStatusManager.class), mock(CategorizationService.class),
                persistence, accountService);
        service.setBatchMatcher(fixture.matcher);
        UUID userId = UUID.randomUUID();
        accountId = UUID.randomUUID();
        UserContext.setCurrentUserId(userId);
        User user = new User();
        user.setId(userId);
        account = new Account();
        account.setId(accountId);
        account.setUser(user);
        account.setType(AccountType.credit_card);
        when(accountService.getAccountById(accountId)).thenReturn(account);
        when(userRepository.getReferenceById(userId)).thenReturn(user);
        doAnswer(inv -> {
            for (Transaction t : (List<Transaction>) inv.getArgument(0)) fixture.store(t);
            return null;
        }).when(dbHandler).saveTransactions(any(), any());
        statement = new Statement();
        statement.setId(UUID.randomUUID());
        statement.setPeriodStart(LocalDate.of(2026, 9, 1));
        statement.setPeriodEnd(LocalDate.of(2026, 9, 30));
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private byte[] parse(String content, ParsedStatementLine... lines) {
        byte[] bytes = content.getBytes();
        when(parser.parse(eq(bytes), nullable(String.class))).thenReturn(StatementExtractionResult.success(List.of(lines), "1234",
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30), null));
        return bytes;
    }

    private static ParsedStatementLine line(int day, String amount, String text) {
        return new ParsedStatementLine(LocalDate.of(2026, 9, day), new BigDecimal(amount), "DEBIT", text, null, null);
    }

    @Test
    void onlyTheMissingLineIsCreatedAndLinkedBeyondTheExistingIndexes() {
        Transaction edited = new Transaction();
        edited.setAccount(account);
        edited.setSource(TransactionSource.file_upload);
        edited.setDate(LocalDate.of(2026, 9, 5));
        edited.setAmount(new BigDecimal("100"));
        edited.setType(TransactionType.DEBIT);
        edited.setDescription("My own words");
        fixture.store(edited);
        fixture.links.add(new StatementTransaction(statement.getId(), edited.getId(), 4, null, null));
        UUID extra = UUID.randomUUID();
        byte[] bytes = parse("a", line(5, "100", "SWIGGY"), line(20, "250", "AMAZON"));
        when(persistence.refreshReconciled(eq(accountId), any(), nullable(StatementDraft.class), any(), any())).thenReturn(Optional.of(
                new StatementPersistenceService.Refreshed(statement, List.of("total_amount_due", "payment_due_date"), List.of(1), List.of(extra))));

        FileIngestionResult res = service.ingest(accountId, List.of(new UploadedFile("s.pdf", "application/pdf", bytes)));

        assertThat(res.totalCreated()).isEqualTo(1);
        assertThat(fixture.txns).hasSize(2);
        Transaction made = fixture.txns.stream().filter(t -> t != edited).findFirst().orElseThrow();
        assertThat(made.getAmount()).isEqualByComparingTo("250");
        assertThat(made.getSource()).isEqualTo(TransactionSource.file_upload);
        assertThat(fixture.links).filteredOn(l -> l.getId().getTransactionId().equals(made.getId()))
                .singleElement().satisfies(l -> {
                    assertThat(l.getId().getStatementId()).isEqualTo(statement.getId());
                    assertThat(l.getLineIndex()).isEqualTo(1);
                });
        assertThat(edited.getDescription()).isEqualTo("My own words");
        assertThat(edited.getDate()).isEqualTo(LocalDate.of(2026, 9, 5));
        FileIngestionResult.FileSummary s = res.fileDetails().get(0);
        assertThat(s.status()).isEqualTo("SUCCESS");
        assertThat(s.created()).isEqualTo(1);
        assertThat(s.filledDetails()).containsExactly("total_amount_due", "payment_due_date");
        assertThat(s.warning()).contains("updated total amount due, payment due date").contains("added 1 missing row")
                .contains("1 row already on this statement are not in the file");
        verify(persistence, never()).createIfNew(any(), any(), any(), any(), any(), any());
        verify(persistence, never()).refreshFromReupload(any(), any(), any());
    }

    @Test
    void nothingChangedIsSkippedAndTouchesNoTransactions() {
        byte[] bytes = parse("b", line(5, "100", "SWIGGY"));
        when(persistence.refreshReconciled(eq(accountId), any(), nullable(StatementDraft.class), any(), any())).thenReturn(Optional.of(
                new StatementPersistenceService.Refreshed(statement, List.of(), List.of(), List.of())));

        FileIngestionResult res = service.ingest(accountId, List.of(new UploadedFile("s.pdf", "application/pdf", bytes)));

        FileIngestionResult.FileSummary s = res.fileDetails().get(0);
        assertThat(s.status()).isEqualTo("SKIPPED");
        assertThat(s.errorMessage()).isEqualTo("Statement already ingested; nothing new to fill");
        assertThat(res.totalCreated()).isZero();
        verify(dbHandler, never()).saveTransactions(any(), any());
    }

    @Test
    void headerOnlyChangeIsReportedAsSuccessWithNoRowsAdded() {
        byte[] bytes = parse("c", line(5, "100", "SWIGGY"));
        when(persistence.refreshReconciled(eq(accountId), any(), nullable(StatementDraft.class), any(), any())).thenReturn(Optional.of(
                new StatementPersistenceService.Refreshed(statement, List.of("closing_balance"), List.of(), List.of())));

        FileIngestionResult.FileSummary s = service.ingest(accountId,
                List.of(new UploadedFile("s.pdf", "application/pdf", bytes))).fileDetails().get(0);

        assertThat(s.status()).isEqualTo("SUCCESS");
        assertThat(s.warning()).isEqualTo("Statement refreshed from this file: updated closing balance");
    }

    @Test
    void aSecondFileOfTheSameCallForAStatementThisCallCreatedTakesTheOldPath() {
        UUID created = UUID.randomUUID();
        Statement fresh = new Statement();
        fresh.setId(created);
        fresh.setPeriodStart(LocalDate.of(2026, 9, 1));
        fresh.setPeriodEnd(LocalDate.of(2026, 9, 30));
        byte[] a = parse("A", line(5, "100", "SWIGGY"));
        byte[] b = parse("B", line(5, "100", "SWIGGY"));
        when(persistence.refreshReconciled(eq(accountId), any(), nullable(StatementDraft.class), any(), any())).thenAnswer(inv -> {
            java.util.Set<UUID> skip = inv.getArgument(4);
            return skip.contains(created) ? Optional.empty() : Optional.of(
                    new StatementPersistenceService.Refreshed(fresh, List.of("verdict"), List.of(), List.of()));
        });
        when(persistence.createIfNew(any(), any(), any(), eq("a.pdf"), any(), nullable(StatementDraft.class))).thenReturn(Optional.of(fresh));
        when(persistence.createIfNew(any(), any(), any(), eq("b.pdf"), any(), nullable(StatementDraft.class))).thenReturn(Optional.empty());
        // file A: the target is not "live" yet at refresh time (nothing to refresh), so it is created
        when(persistence.refreshReconciled(eq(accountId), eq(StatementPersistenceService.sha256Hex(a)), nullable(StatementDraft.class), any(), any()))
                .thenReturn(Optional.empty());

        FileIngestionResult res = service.ingest(accountId, List.of(new UploadedFile("a.pdf", "application/pdf", a),
                new UploadedFile("b.pdf", "application/pdf", b)));

        assertThat(res.fileDetails().get(0).status()).isEqualTo("SUCCESS");
        FileIngestionResult.FileSummary second = res.fileDetails().get(1);
        assertThat(second.status()).isEqualTo("SKIPPED");
        assertThat(second.errorMessage()).isEqualTo("Statement already ingested (same period or file)");
        assertThat(fixture.txns).hasSize(1);
    }

    @Test
    void aFailureCreatingTheMissingRowsRollsTheWholeRefreshBack() {
        org.springframework.transaction.PlatformTransactionManager tm = mock(org.springframework.transaction.PlatformTransactionManager.class);
        service.setTransactionManager(tm);
        byte[] bytes = parse("d", line(20, "250", "AMAZON"));
        when(persistence.refreshReconciled(eq(accountId), any(), nullable(StatementDraft.class), any(), any())).thenReturn(Optional.of(
                new StatementPersistenceService.Refreshed(statement, List.of("verdict"), List.of(0), List.of())));
        doAnswer(inv -> {
            throw new IllegalStateException("db down");
        }).when(dbHandler).saveTransactions(any(), any());

        FileIngestionResult res = service.ingest(accountId, List.of(new UploadedFile("s.pdf", "application/pdf", bytes)));

        assertThat(res.fileDetails().get(0).status()).isEqualTo("FAILED");
        verify(tm).rollback(any());
        verify(tm, never()).commit(any());
    }
}
