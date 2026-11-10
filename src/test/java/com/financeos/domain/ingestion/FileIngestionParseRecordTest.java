package com.financeos.domain.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.doThrow;
import com.financeos.domain.statement.StatementParseRecords;
import org.mockito.InOrder;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
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
import com.financeos.domain.transaction.ReviewStatusManager;
import com.financeos.domain.transaction.TransactionMatcher;
import com.financeos.domain.transaction.match.StatementBatchMatcher;
import com.financeos.domain.user.User;
import com.financeos.domain.user.UserRepository;
import com.financeos.gmail.reconcile.ParsedStatementLine;
import com.financeos.gmail.reconcile.StatementExtractionResult;
import com.financeos.gmail.reconcile.StatementParser;
import com.financeos.statement.StatementParseMetrics;
import com.financeos.statement.parser.StatementParseException.Kind;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The upload path writes the parse record only after the statement transaction, and only for a created or reconciled-refreshed statement. */
class FileIngestionParseRecordTest {

    private final StatementParser parser = mock(StatementParser.class);
    private final StatementPersistenceService persistence = mock(StatementPersistenceService.class);
    private final StatementParseRecords records = mock(StatementParseRecords.class);
    private FileIngestionService service;
    private UUID accountId;

    @BeforeEach
    void setUp() {
        AccountService accountService = mock(AccountService.class);
        UserRepository userRepository = mock(UserRepository.class);
        service = new FileIngestionService(mock(AccountRepository.class), userRepository, parser,
                mock(FileIngestionDbHandler.class), mock(TransactionMatcher.class), mock(ReviewStatusManager.class),
                mock(CategorizationService.class), persistence, accountService);
        StatementBatchMatcher batchMatcher = mock(StatementBatchMatcher.class);
        service.setBatchMatcher(batchMatcher);
        service.setParseRecords(records);
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
        when(batchMatcher.match(any(), any(), any(), any())).thenReturn(new StatementBatchMatcher.Result(0, 0, 0, 0, 0, List.of()));
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private static StatementDraft draft(StatementVerdict verdict) {
        return new StatementDraft("bank_account", LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30), null, null, "Bank",
                "1234", 1, 0, BigDecimal.TEN, BigDecimal.ZERO, "heuristic", null, null, verdict, null, null,
                java.util.Set.of(), 2, "{\"snapshot\":1}");
    }

    private byte[] parsed(String content, StatementDraft draft) {
        byte[] bytes = content.getBytes();
        ParsedStatementLine line = new ParsedStatementLine(LocalDate.of(2026, 9, 5), BigDecimal.TEN, "DEBIT", "Txn", null, null);
        when(parser.parse(eq(bytes), nullable(String.class))).thenReturn(StatementExtractionResult.success(
                List.of(line), "1234", LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30), draft));
        return bytes;
    }

    private FileIngestionResult ingest(byte[] bytes) {
        return service.ingest(accountId, List.of(new UploadedFile("s.pdf", "application/pdf", bytes)));
    }

    @Test
    void aCreatedStatementGetsItsRecordWrittenAfterItWasCreated() {
        Statement s = new Statement();
        s.setId(UUID.randomUUID());
        when(persistence.createIfNew(any(), any(), any(), any(), any(), nullable(StatementDraft.class))).thenReturn(Optional.of(s));

        ingest(parsed("a", draft(StatementVerdict.AUTO_INGEST)));

        InOrder order = inOrder(persistence, records);
        order.verify(persistence).createIfNew(any(), any(), any(), any(), any(), nullable(StatementDraft.class));
        order.verify(records).saveQuietly(s.getId(), 2, "{\"snapshot\":1}");
    }

    @Test
    void aReconcilingRefreshWritesTheNewRecordAfterTheRefreshTransaction() {
        Statement s = new Statement();
        s.setId(UUID.randomUUID());
        when(persistence.refreshReconciled(any(), any(), nullable(StatementDraft.class), any(), any())).thenReturn(Optional.of(
                new StatementPersistenceService.Refreshed(s, List.of(), List.of(), List.of())));

        ingest(parsed("b", draft(StatementVerdict.AUTO_INGEST)));

        InOrder order = inOrder(persistence, records);
        order.verify(persistence).refreshReconciled(any(), any(), nullable(StatementDraft.class), any(), any());
        order.verify(records).saveQuietly(s.getId(), 2, "{\"snapshot\":1}");
        verify(persistence, never()).createIfNew(any(), any(), any(), any(), any(), any());
    }

    @Test
    void aFillBlanksRefreshWritesNoRecord() {
        when(persistence.refreshFromReupload(eq(accountId), any(), nullable(StatementDraft.class))).thenReturn(Optional.of(List.of()));

        ingest(parsed("c", draft(StatementVerdict.AUTO_INGEST)));

        verify(records, never()).saveQuietly(any(), any(), any());
    }

    @Test
    void anAlreadyIngestedFileWritesNoRecord() {
        when(persistence.createIfNew(any(), any(), any(), any(), any(), nullable(StatementDraft.class))).thenReturn(Optional.empty());

        ingest(parsed("d", draft(StatementVerdict.AUTO_INGEST)));

        verify(records, never()).saveQuietly(any(), any(), any());
    }

    @Test
    void aBlockedParseWritesNoRecord() {
        StatementDraft contradicted = new StatementDraft("bank_account", LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30), null, null,
                "Bank", "1234", 1, 0, BigDecimal.TEN, BigDecimal.ZERO, "opening_closing", null, Boolean.FALSE, StatementVerdict.REJECTED,
                null, null, java.util.Set.of(), 2, "{}");

        assertThat(ingest(parsed("g", contradicted)).fileDetails().get(0).status()).isEqualTo("FAILED");

        verify(records, never()).saveQuietly(any(), any(), any());
    }

    @Test
    void aFailedParseWritesNoRecord() {
        byte[] failed = "f".getBytes();
        when(parser.parse(eq(failed), nullable(String.class))).thenReturn(StatementExtractionResult.failure("ERROR: failed to read PDF."));

        ingest(failed);

        verify(records, never()).saveQuietly(any(), any(), any());
    }

    @Test
    void nothingIsWrittenWhenTheRefreshTransactionFails() {
        when(persistence.refreshReconciled(any(), any(), nullable(StatementDraft.class), any(), any()))
                .thenThrow(new IllegalStateException("rolled back"));

        FileIngestionResult res = ingest(parsed("h", draft(StatementVerdict.AUTO_INGEST)));

        assertThat(res.fileDetails().get(0).status()).isEqualTo("FAILED");
        verify(records, never()).saveQuietly(any(), any(), any());
    }

    @Test
    void aDraftWithoutAVersionIsHandedOverAsNullsAndTheRecordsServiceIgnoresIt() {
        Statement s = new Statement();
        s.setId(UUID.randomUUID());
        when(persistence.createIfNew(any(), any(), any(), any(), any(), nullable(StatementDraft.class))).thenReturn(Optional.of(s));
        StatementDraft plain = new StatementDraft("bank_account", LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30), null, null, "Bank",
                "1234", 1, 0, BigDecimal.TEN, BigDecimal.ZERO, "heuristic", null, null, StatementVerdict.AUTO_INGEST, null, null);

        ingest(parsed("i", plain));

        verify(records).saveQuietly(s.getId(), null, null);
    }

    @Test
    void aServiceBuiltWithoutRecordsStillIngests() {
        service.setParseRecords(null);
        Statement s = new Statement();
        s.setId(UUID.randomUUID());
        when(persistence.createIfNew(any(), any(), any(), any(), any(), nullable(StatementDraft.class))).thenReturn(Optional.of(s));

        assertThat(ingest(parsed("j", draft(StatementVerdict.AUTO_INGEST))).fileDetails().get(0).status()).isEqualTo("SUCCESS");
    }
}
