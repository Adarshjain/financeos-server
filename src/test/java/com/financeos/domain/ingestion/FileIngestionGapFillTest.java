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
import com.financeos.domain.transaction.ReviewStatusManager;
import com.financeos.domain.transaction.TransactionMatcher;
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

/** A re-upload of a card statement that lacks figures is reported as filled / nothing new, with no duplicate created. */
class FileIngestionGapFillTest {

    private final StatementParser parser = mock(StatementParser.class);
    private final StatementPersistenceService persistence = mock(StatementPersistenceService.class);
    private FileIngestionService service;
    private UUID accountId;

    @BeforeEach
    void setUp() {
        AccountService accountService = mock(AccountService.class);
        UserRepository userRepository = mock(UserRepository.class);
        service = new FileIngestionService(mock(AccountRepository.class), userRepository, parser,
                mock(FileIngestionDbHandler.class), mock(TransactionMatcher.class), mock(ReviewStatusManager.class),
                mock(CategorizationService.class), persistence, accountService);
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
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private byte[] parseOk(String content) {
        byte[] bytes = content.getBytes();
        ParsedStatementLine line = new ParsedStatementLine(LocalDate.of(2026, 9, 5), new BigDecimal("10"), "debit", "Txn", null, null);
        when(parser.parse(eq(bytes), nullable(String.class))).thenReturn(
                StatementExtractionResult.success(List.of(line), "1234", LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30), null));
        return bytes;
    }

    @Test
    void reportsTheFilledFieldsAndCreatesNothing() {
        byte[] bytes = parseOk("a");
        when(persistence.fillMissingDetails(eq(accountId), any(), nullable(StatementDraft.class)))
                .thenReturn(Optional.of(List.of("TOTAL_DUE", "DUE_DATE")));

        FileIngestionResult res = service.ingest(accountId, List.of(new UploadedFile("s.pdf", "application/pdf", bytes)));

        FileIngestionResult.FileSummary s = res.fileDetails().get(0);
        assertThat(s.status()).isEqualTo("SKIPPED");
        assertThat(s.errorMessage()).isEqualTo("Statement already ingested; filled in total due, due date");
        assertThat(s.filledDetails()).containsExactly("TOTAL_DUE", "DUE_DATE");
        assertThat(res.totalCreated()).isZero();
        verify(persistence, never()).createIfNew(any(), any(), any(), any(), any(), any());
    }

    @Test
    void reportsNothingNewWhenTheFileHasNothingToFill() {
        byte[] bytes = parseOk("b");
        when(persistence.fillMissingDetails(eq(accountId), any(), nullable(StatementDraft.class)))
                .thenReturn(Optional.of(List.of()));

        FileIngestionResult.FileSummary s = service.ingest(accountId,
                List.of(new UploadedFile("s.pdf", "application/pdf", bytes))).fileDetails().get(0);

        assertThat(s.status()).isEqualTo("SKIPPED");
        assertThat(s.errorMessage()).isEqualTo("Statement already ingested; nothing new to fill");
        assertThat(s.filledDetails()).isEmpty();
    }

    @Test
    void anOrdinaryDuplicateKeepsTodaysMessageAndNoFilledList() {
        byte[] bytes = parseOk("c");
        when(persistence.fillMissingDetails(eq(accountId), any(), nullable(StatementDraft.class))).thenReturn(Optional.empty());
        when(persistence.createIfNew(any(), any(), any(), any(), any(), nullable(StatementDraft.class))).thenReturn(Optional.empty());

        FileIngestionResult.FileSummary s = service.ingest(accountId,
                List.of(new UploadedFile("s.pdf", "application/pdf", bytes))).fileDetails().get(0);

        assertThat(s.errorMessage()).isEqualTo("Statement already ingested (same period or file)");
        assertThat(s.filledDetails()).isNull();
    }
}
