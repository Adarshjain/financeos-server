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
import com.financeos.domain.statement.StatementVerdict;
import com.financeos.domain.transaction.ReviewReason;
import com.financeos.domain.transaction.match.MatchFixture;
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
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.*;

class FileIngestionStatementDuplicateTest {

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

        when(accountService.getAccountById(accountId)).thenReturn(account);
        when(userRepository.getReferenceById(userId)).thenReturn(user);

        fixture = new MatchFixture();
        ingestionService.setBatchMatcher(fixture.matcher);
        doAnswer(inv -> {
            List<Transaction> txs = inv.getArgument(0);
            for (Transaction t : txs) {
                fixture.store(t);
            }
            return null;
        }).when(dbHandler).saveTransactions(any(), any());
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private MatchFixture fixture;

    private FileIngestionResult ingestOneLine(Transaction existing, String lineText) {
        byte[] bytes = "f".getBytes();
        ParsedStatementLine line = new ParsedStatementLine(LocalDate.of(2026, 8, 12), new BigDecimal("450.00"), "debit", lineText, new BigDecimal("1000.00"), true);
        when(statementParser.parse(eq(bytes), nullable(String.class))).thenReturn(StatementExtractionResult.success(
                List.of(line), "1234", LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31), null));
        Statement stmt = new Statement();
        stmt.setId(UUID.randomUUID());
        stmt.setPeriodStart(LocalDate.of(2026, 8, 1));
        stmt.setPeriodEnd(LocalDate.of(2026, 8, 31));
        when(statementPersistenceService.createIfNew(eq(user), eq(account), eq(StatementSource.file_upload), eq("a.pdf"), any(), nullable(StatementDraft.class)))
                .thenReturn(Optional.of(stmt));
        fixture.store(existing);
        return ingestionService.ingest(accountId, List.of(new UploadedFile("a.pdf", "application/pdf", bytes)));
    }

    private Transaction manualRow(int day) {
        Transaction manual = new Transaction();
        manual.setAccount(account);
        manual.setSource(TransactionSource.manual);
        manual.setDate(LocalDate.of(2026, 8, day));
        manual.setAmount(new BigDecimal("450.00"));
        manual.setType(TransactionType.DEBIT);
        manual.setDescription("Swiggy");
        return manual;
    }

    @Test
    void uploadAttachesItsLineToAManualRowADayEarlierAndTheStatementWins() {
        Transaction manual = manualRow(11);

        FileIngestionResult res = ingestOneLine(manual, "UPI/DR/4123/SWIGGY/YESB");

        assertThat(res.totalCreated()).isZero();
        assertThat(res.totalDuplicatesFound()).isZero();
        assertThat(fixture.txns).containsExactly(manual);
        assertThat(manual.getDate()).isEqualTo(LocalDate.of(2026, 8, 12));
        assertThat(manual.getSourcedDescription()).isEqualTo("UPI/DR/4123/SWIGGY/YESB");
        assertThat(manual.getDescription()).isEqualTo("Swiggy");
        assertThat(fixture.statementsOf(manual)).hasSize(1);
    }

    @Test
    void aManualRowOutsideTheBankWindowIsNotPairedAndNeverTagged() {
        Transaction manual = manualRow(10);

        FileIngestionResult res = ingestOneLine(manual, "UPI/DR/4123/ZOMATO/YESB");

        assertThat(res.totalCreated()).isEqualTo(1);
        assertThat(res.totalDuplicatesFound()).isZero();
        assertThat(fixture.txns).hasSize(2);
        assertThat(manual.getReviewReasons()).isEmpty();
        assertThat(manual.getNotOnStatementId()).isNull();
        assertThat(fixture.statementsOf(manual)).isEmpty();
    }
}
