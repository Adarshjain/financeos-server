package com.financeos.domain.ingestion;

import com.financeos.core.security.UserContext;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.account.AccountType;
import com.financeos.domain.account.AccountBankDetails;
import com.financeos.domain.categorization.CategorizationService;
import com.financeos.domain.statement.Statement;
import com.financeos.domain.statement.StatementDraft;
import com.financeos.domain.statement.StatementPersistenceService;
import com.financeos.domain.statement.StatementSource;
import com.financeos.domain.transaction.ReviewReason;
import com.financeos.domain.transaction.ReviewStatusManager;
import com.financeos.domain.transaction.Transaction;
import com.financeos.domain.transaction.TransactionMatcher;
import com.financeos.domain.transaction.TransactionSource;
import com.financeos.domain.transaction.TransactionType;
import com.financeos.domain.user.User;
import com.financeos.domain.user.UserRepository;
import com.financeos.gmail.reconcile.ParsedStatementLine;
import com.financeos.gmail.reconcile.StatementExtractionResult;
import com.financeos.gmail.reconcile.StatementParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.*;

class FileIngestionServiceTest {

    private AccountRepository accountRepository;
    private UserRepository userRepository;
    private StatementParser statementParser;
    private FileIngestionDbHandler dbHandler;
    private TransactionMatcher transactionMatcher;
    private ReviewStatusManager reviewStatusManager;
    private CategorizationService categorizationService;
    private StatementPersistenceService statementPersistenceService;
    private FileIngestionService ingestionService;

    private UUID userId;
    private UUID accountId;
    private User user;
    private Account account;

    @BeforeEach
    void setUp() {
        accountRepository = mock(AccountRepository.class);
        userRepository = mock(UserRepository.class);
        statementParser = mock(StatementParser.class);
        dbHandler = mock(FileIngestionDbHandler.class);
        transactionMatcher = mock(TransactionMatcher.class);
        reviewStatusManager = mock(ReviewStatusManager.class);
        categorizationService = mock(CategorizationService.class);
        statementPersistenceService = mock(StatementPersistenceService.class);

        com.financeos.domain.account.AccountService accountService = mock(com.financeos.domain.account.AccountService.class);
        when(accountService.extractLast4(any())).thenAnswer(inv -> {
            Account acc = inv.getArgument(0);
            if (acc.getBankDetails() != null) return acc.getBankDetails().getLast4();
            return "1234";
        });

        ingestionService = new FileIngestionService(
                accountRepository,
                userRepository,
                statementParser,
                dbHandler,
                transactionMatcher,
                reviewStatusManager,
                categorizationService,
                statementPersistenceService,
                accountService
        );

        userId = UUID.randomUUID();
        accountId = UUID.randomUUID();
        UserContext.setCurrentUserId(userId);

        user = new User();
        user.setId(userId);

        account = new Account();
        account.setId(accountId);
        account.setUser(user);
        account.setType(AccountType.bank_account);
        AccountBankDetails bankDetails = new AccountBankDetails();
        bankDetails.setLast4("1234");
        account.setBankDetails(bankDetails);

        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));
        when(userRepository.getReferenceById(userId)).thenReturn(user);

        doAnswer(inv -> {
            List<Transaction> txs = inv.getArgument(0);
            for (Transaction t : txs) {
                if (t.getId() == null) {
                    t.setId(UUID.randomUUID());
                }
            }
            return null;
        }).when(dbHandler).saveTransactions(any(), any());
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void testPerFileAttributionAndDuplicatesAndLineIndexFix() {
        byte[] bytes1 = "file1_content".getBytes();
        byte[] bytes2 = "file2_content".getBytes();

        UploadedFile file1 = new UploadedFile("file1.pdf", "application/pdf", bytes1);
        UploadedFile file2 = new UploadedFile("file2.pdf", "application/pdf", bytes2);

        ParsedStatementLine line1 = new ParsedStatementLine(LocalDate.of(2026, 8, 1), new BigDecimal("100.00"), "debit", "Txn 1", new BigDecimal("1000.00"), true);
        ParsedStatementLine line2 = new ParsedStatementLine(LocalDate.of(2026, 8, 2), new BigDecimal("200.00"), "credit", "Txn 2", new BigDecimal("1200.00"), true);
        StatementExtractionResult result1 = StatementExtractionResult.success(List.of(line1, line2), "1234", LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 2), null);

        ParsedStatementLine line3 = new ParsedStatementLine(LocalDate.of(2026, 8, 3), new BigDecimal("300.00"), "debit", "Txn 3", new BigDecimal("900.00"), true);
        StatementExtractionResult result2 = StatementExtractionResult.success(List.of(line3), "1234", LocalDate.of(2026, 8, 3), LocalDate.of(2026, 8, 3), null);

        when(statementParser.parse(eq(bytes1), nullable(String.class))).thenReturn(result1);
        when(statementParser.parse(eq(bytes2), nullable(String.class))).thenReturn(result2);

        Statement stmt1 = new Statement();
        stmt1.setId(UUID.randomUUID());
        Statement stmt2 = new Statement();
        stmt2.setId(UUID.randomUUID());

        when(statementPersistenceService.createIfNew(eq(user), eq(account), eq(StatementSource.file_upload), eq("file1.pdf"), any(), nullable(StatementDraft.class)))
                .thenReturn(Optional.of(stmt1));
        when(statementPersistenceService.createIfNew(eq(user), eq(account), eq(StatementSource.file_upload), eq("file2.pdf"), any(), nullable(StatementDraft.class)))
                .thenReturn(Optional.of(stmt2));

        Transaction dbTx = new Transaction();
        dbTx.setId(UUID.randomUUID());
        dbTx.setDate(LocalDate.of(2026, 8, 3));
        dbTx.setAmount(new BigDecimal("300.00"));
        dbTx.setSourcedDescription("Txn 3");

        when(dbHandler.findExistingTransactions(eq(accountId), any(LocalDate.class), any(LocalDate.class))).thenReturn(List.of(dbTx));

        when(transactionMatcher.areDuplicates(any(), any(), anyInt())).thenAnswer(invocation -> {
            Transaction tx1 = invocation.getArgument(0);
            Transaction tx2 = invocation.getArgument(1);
            return tx1 != null && tx2 != null && tx1.getSourcedDescription() != null && tx1.getSourcedDescription().equals(tx2.getSourcedDescription());
        });


        FileIngestionResult res = ingestionService.ingest(accountId, List.of(file1, file2));

        assertThat(res.filesProcessed()).isEqualTo(2);
        assertThat(res.totalCreated()).isEqualTo(3);
        assertThat(res.totalDuplicatesFound()).isEqualTo(1);
        assertThat(res.duplicateDetails()).hasSize(1);

        FileIngestionResult.DuplicateDetail dup = res.duplicateDetails().get(0);
        assertThat(dup.filename()).isEqualTo("file2.pdf");
        assertThat(dup.amount()).isEqualTo(new BigDecimal("300.00"));
        assertThat(dup.matchedTransactionId()).isEqualTo(dbTx.getId());

        assertThat(res.fileDetails()).hasSize(2);
        assertThat(res.fileDetails().get(0).created()).isEqualTo(2);
        assertThat(res.fileDetails().get(0).duplicates()).isEqualTo(0);
        assertThat(res.fileDetails().get(1).created()).isEqualTo(1);
        assertThat(res.fileDetails().get(1).duplicates()).isEqualTo(1);

        // Verify TxnLink line index (j fix: 0, 1 for file1; 0 for file2)
        ArgumentCaptor<List<StatementPersistenceService.TxnLink>> linksCaptor = ArgumentCaptor.forClass(List.class);
        verify(statementPersistenceService).linkTransactions(eq(stmt1.getId()), linksCaptor.capture());
        List<StatementPersistenceService.TxnLink> file1Links = linksCaptor.getValue();
        assertThat(file1Links.get(0).lineIndex()).isEqualTo(0);
        assertThat(file1Links.get(1).lineIndex()).isEqualTo(1);
    }

    @Test
    void testWarningAndSkippedFileSummaries() {
        byte[] bytes1 = "file1_content".getBytes();
        byte[] bytes2 = "file2_content".getBytes();

        UploadedFile file1 = new UploadedFile("file1.pdf", "application/pdf", bytes1);
        UploadedFile file2 = new UploadedFile("file2.pdf", "application/pdf", bytes2);

        // file1 has mismatching account number ("9999" vs account "1234") -> SUCCESS with warning
        ParsedStatementLine line1 = new ParsedStatementLine(LocalDate.of(2026, 8, 1), new BigDecimal("100.00"), "debit", "Txn 1", new BigDecimal("1000.00"), true);
        StatementExtractionResult result1 = StatementExtractionResult.success(List.of(line1), "9999", LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 1), null);

        when(statementParser.parse(eq(bytes1), nullable(String.class))).thenReturn(result1);
        Statement stmt1 = new Statement();
        stmt1.setId(UUID.randomUUID());
        when(statementPersistenceService.createIfNew(eq(user), eq(account), eq(StatementSource.file_upload), eq("file1.pdf"), any(), nullable(StatementDraft.class)))
                .thenReturn(Optional.of(stmt1));

        // file2 is already ingested -> SKIPPED
        StatementExtractionResult result2 = StatementExtractionResult.success(List.of(line1), "1234", LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 1), null);
        when(statementParser.parse(eq(bytes2), nullable(String.class))).thenReturn(result2);
        when(statementPersistenceService.createIfNew(eq(user), eq(account), eq(StatementSource.file_upload), eq("file2.pdf"), any(), nullable(StatementDraft.class)))
                .thenReturn(Optional.empty());

        FileIngestionResult res = ingestionService.ingest(accountId, List.of(file1, file2));

        assertThat(res.fileDetails()).hasSize(2);
        FileIngestionResult.FileSummary sum1 = res.fileDetails().get(0);
        assertThat(sum1.status()).isEqualTo("SUCCESS");
        assertThat(sum1.errorMessage()).isNull();
        assertThat(sum1.warning()).contains("Warning: statement account number does not match this account");

        FileIngestionResult.FileSummary sum2 = res.fileDetails().get(1);
        assertThat(sum2.status()).isEqualTo("SKIPPED");
        assertThat(sum2.errorMessage()).contains("Statement already ingested");
        assertThat(sum2.warning()).isNull();
    }

    @Test
    void testDuplicateCappingAt50() {
        byte[] bytes = "file_content".getBytes();
        UploadedFile file = new UploadedFile("file.pdf", "application/pdf", bytes);

        List<ParsedStatementLine> lines = new ArrayList<>();
        for (int i = 0; i < 55; i++) {
            lines.add(new ParsedStatementLine(LocalDate.of(2026, 8, 1), new BigDecimal("10.00"), "debit", "Dup " + i, new BigDecimal("100.00"), true));
        }
        StatementExtractionResult parseResult = StatementExtractionResult.success(lines, "1234", LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 1), null);
        when(statementParser.parse(eq(bytes), nullable(String.class))).thenReturn(parseResult);


        Statement stmt = new Statement();
        stmt.setId(UUID.randomUUID());
        when(statementPersistenceService.createIfNew(any(), any(), any(), any(), any(), nullable(StatementDraft.class))).thenReturn(Optional.of(stmt));




        // Create 55 DB duplicates
        List<Transaction> dbTxns = new ArrayList<>();
        for (int i = 0; i < 55; i++) {
            Transaction dbTx = new Transaction();
            dbTx.setId(UUID.randomUUID());
            dbTx.setDate(LocalDate.of(2026, 8, 1));
            dbTx.setAmount(new BigDecimal("10.00"));
            dbTx.setSourcedDescription("Dup " + i);
            dbTxns.add(dbTx);
        }
        when(dbHandler.findExistingTransactions(any(), any(), any())).thenReturn(dbTxns);

        when(transactionMatcher.areDuplicates(any(), any(), anyInt())).thenAnswer(inv -> {
            Transaction t1 = inv.getArgument(0);
            Transaction t2 = inv.getArgument(1);
            return t1.getSourcedDescription().equals(t2.getSourcedDescription());
        });

        FileIngestionResult res = ingestionService.ingest(accountId, List.of(file));

        assertThat(res.totalDuplicatesFound()).isEqualTo(55);
        assertThat(res.duplicateDetails()).hasSize(50);
        assertThat(res.duplicatesTruncated()).isEqualTo(5);
    }
}
