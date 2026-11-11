package com.financeos.domain.ingestion;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
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
import org.mockito.InOrder;
import org.junit.jupiter.api.Test;

/** A different-bytes copy of a statement is offered to the attacher at each upload exit; the statement's own file is not. */
class FileIngestionCopyAttachTest {

    private final StatementParser parser = mock(StatementParser.class);
    private final StatementPersistenceService persistence = mock(StatementPersistenceService.class);
    private final StatementFileAttacher attacher = mock(StatementFileAttacher.class);
    private FileIngestionService service;
    private UUID userId;
    private UUID accountId;

    @BeforeEach
    void setUp() {
        AccountService accountService = mock(AccountService.class);
        UserRepository userRepository = mock(UserRepository.class);
        StatementBatchMatcher batchMatcher = mock(StatementBatchMatcher.class);
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

    private StatementDraft parsed(byte[] bytes) {
        StatementDraft draft = new StatementDraft("bank_account", LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30), null, null,
                "Bank", "1234", 1, 0, BigDecimal.TEN, BigDecimal.ZERO, "heuristic", null, null, StatementVerdict.AUTO_INGEST, null, null);
        ParsedStatementLine line = new ParsedStatementLine(LocalDate.of(2026, 9, 5), new BigDecimal("10"), "DEBIT", "Txn", null, null);
        when(parser.parse(eq(bytes), nullable(String.class))).thenReturn(StatementExtractionResult.success(
                List.of(line), "1234", LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30), draft));
        return draft;
    }

    private void ingest(byte[] bytes) {
        service.ingest(accountId, List.of(new UploadedFile("s.pdf", "application/pdf", bytes)));
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
    void aRefreshFromOtherBytesOffersTheCopyWithTheRefreshedId() {
        byte[] bytes = "copy-a".getBytes();
        StatementDraft draft = parsed(bytes);
        Statement s = refreshing("some-other-sha");

        ingest(bytes);

        verify(attacher).attachCopyOfSameStatement(userId, accountId, StatementPersistenceService.sha256Hex(bytes), draft, bytes, "s.pdf", s.getId());
    }

    @Test
    void aRefreshFromTheStatementsOwnBytesDoesNotOfferACopy() {
        byte[] bytes = "copy-b".getBytes();
        parsed(bytes);
        Statement s = refreshing(StatementPersistenceService.sha256Hex(bytes));

        ingest(bytes);

        verify(attacher).attach(userId, s.getId(), bytes, "s.pdf");
        verify(attacher, never()).attachCopyOfSameStatement(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void theProofIsTakenBeforeTheGapFillAndItsIdIsPassedOn() {
        byte[] bytes = "copy-g".getBytes();
        StatementDraft draft = parsed(bytes);
        UUID proven = UUID.randomUUID();
        when(attacher.provenCopyTarget(accountId, draft)).thenReturn(Optional.of(proven));
        when(persistence.refreshFromReupload(eq(accountId), any(), nullable(StatementDraft.class))).thenReturn(Optional.of(List.of()));

        ingest(bytes);

        InOrder order = inOrder(attacher, persistence);
        order.verify(attacher).provenCopyTarget(accountId, draft);
        order.verify(persistence).refreshFromReupload(eq(accountId), any(), nullable(StatementDraft.class));
        order.verify(attacher).attachCopyOfSameStatement(userId, accountId, StatementPersistenceService.sha256Hex(bytes), draft, bytes, "s.pdf", proven);
    }

    @Test
    void aThrowingProofCannotFailTheFileAndPassesNoId() {
        byte[] bytes = "copy-h".getBytes();
        StatementDraft draft = parsed(bytes);
        when(attacher.provenCopyTarget(any(), any())).thenThrow(new RuntimeException("db"));
        when(persistence.refreshFromReupload(eq(accountId), any(), nullable(StatementDraft.class))).thenReturn(Optional.of(List.of()));

        ingest(bytes);

        verify(attacher).attachCopyOfSameStatement(eq(userId), eq(accountId), any(), eq(draft), eq(bytes), eq("s.pdf"), isNull());
    }

    @Test
    void aGapFillReUploadOffersTheCopyWithNoRefreshedId() {
        byte[] bytes = "copy-c".getBytes();
        StatementDraft draft = parsed(bytes);
        when(persistence.refreshFromReupload(eq(accountId), any(), nullable(StatementDraft.class))).thenReturn(Optional.of(List.of()));

        ingest(bytes);

        verify(attacher).attachIfStoredStatementLacksFile(eq(userId), eq(accountId), any(), eq(bytes), eq("s.pdf"));
        verify(attacher).attachCopyOfSameStatement(userId, accountId, StatementPersistenceService.sha256Hex(bytes), draft, bytes, "s.pdf", null);
    }

    @Test
    void anAlreadyIngestedFileOffersTheCopyWithNoRefreshedId() {
        byte[] bytes = "copy-d".getBytes();
        StatementDraft draft = parsed(bytes);
        when(persistence.createIfNew(any(), any(), any(), any(), any(), nullable(StatementDraft.class))).thenReturn(Optional.empty());

        ingest(bytes);

        verify(attacher).attachCopyOfSameStatement(eq(userId), eq(accountId), any(), eq(draft), eq(bytes), eq("s.pdf"), isNull());
    }

    @Test
    void aParallelDuplicateOffersTheCopyWithNoRefreshedId() {
        byte[] bytes = "copy-e".getBytes();
        StatementDraft draft = parsed(bytes);
        when(persistence.createIfNew(any(), any(), any(), any(), any(), nullable(StatementDraft.class)))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException("unique"));
        when(persistence.alreadyExists(eq(accountId), nullable(StatementDraft.class), any())).thenReturn(true);

        ingest(bytes);

        verify(attacher).attachCopyOfSameStatement(eq(userId), eq(accountId), any(), eq(draft), eq(bytes), eq("s.pdf"), isNull());
    }

    @Test
    void aThrowingCopyAttachCannotFailTheFile() {
        byte[] bytes = "copy-f".getBytes();
        parsed(bytes);
        when(persistence.createIfNew(any(), any(), any(), any(), any(), nullable(StatementDraft.class))).thenReturn(Optional.empty());
        doThrow(new RuntimeException("store down")).when(attacher).attachCopyOfSameStatement(any(), any(), any(), any(), any(), any(), any());

        service.ingest(accountId, List.of(new UploadedFile("s.pdf", "application/pdf", bytes)));
    }
}
