package com.financeos.domain.ingestion;

import com.financeos.core.security.UserContext;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountBankDetails;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.account.AccountService;
import com.financeos.domain.account.AccountType;
import com.financeos.domain.categorization.CategorizationService;
import com.financeos.domain.statement.StatementPersistenceService;
import com.financeos.domain.transaction.ReviewStatusManager;
import com.financeos.domain.transaction.TransactionMatcher;
import com.financeos.domain.user.User;
import com.financeos.domain.user.UserRepository;
import com.financeos.gmail.reconcile.StatementExtractionResult;
import com.financeos.gmail.reconcile.StatementParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.*;

/** Locked files: the account's password first, then the user's other saved ones; error codes on failure. */
class FileIngestionSavedPasswordsTest {

    private static final String LOCKED = "ERROR: PDF is password-protected and the password is missing or wrong.";

    private StatementParser parser;
    private StatementPasswordVault vault;
    private FileIngestionService service;
    private UUID userId;
    private UUID accountId;
    private Account account;
    private AccountBankDetails details;
    private final byte[] bytes = "locked".getBytes();

    @BeforeEach
    void setUp() {
        parser = mock(StatementParser.class);
        vault = mock(StatementPasswordVault.class);
        AccountService accountService = mock(AccountService.class);
        UserRepository userRepository = mock(UserRepository.class);
        service = new FileIngestionService(mock(AccountRepository.class), userRepository, parser, mock(FileIngestionDbHandler.class),
                mock(TransactionMatcher.class), mock(ReviewStatusManager.class), mock(CategorizationService.class),
                mock(StatementPersistenceService.class), accountService);
        service.setPasswordVault(vault);

        userId = UUID.randomUUID();
        accountId = UUID.randomUUID();
        UserContext.setCurrentUserId(userId);
        User user = new User();
        user.setId(userId);
        account = new Account();
        account.setId(accountId);
        account.setUser(user);
        account.setType(AccountType.bank_account);
        details = new AccountBankDetails();
        account.setBankDetails(details);
        when(accountService.getAccountById(accountId)).thenReturn(account);
        when(userRepository.getReferenceById(userId)).thenReturn(user);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private FileIngestionResult.FileSummary ingestOne() {
        return service.ingest(accountId, List.of(new UploadedFile("a.pdf", "application/pdf", bytes))).fileDetails().get(0);
    }

    @Test
    void triesTheAccountsPasswordThenTheOthersInOrderAndStopsAtTheFirstThatOpens() {
        details.setStatementPassword("own");
        when(vault.savedPasswords(userId)).thenReturn(List.of("own", "second", "third", "fourth"));
        when(parser.parse(eq(bytes), nullable(String.class))).thenReturn(StatementExtractionResult.failure(LOCKED));
        when(parser.parse(bytes, "third")).thenReturn(StatementExtractionResult.success(List.of(), "1234", null, null, null));

        FileIngestionResult.FileSummary file = ingestOne();

        assertThat(file.status()).isEqualTo("SUCCESS");
        InOrder order = inOrder(parser);
        order.verify(parser).parse(bytes, "own");
        order.verify(parser).parse(bytes, "second");
        order.verify(parser).parse(bytes, "third");
        verify(parser, never()).parse(bytes, "fourth");
        // the account already has a password: nothing is saved over it
        verify(vault, never()).saveIfMissing(any(), any(), any());
    }

    @Test
    void anotherPasswordThatOpensIsSavedToAnAccountWithNone() {
        when(vault.savedPasswords(userId)).thenReturn(List.of("other"));
        when(parser.parse(bytes, null)).thenReturn(StatementExtractionResult.failure(LOCKED));
        when(parser.parse(bytes, "other")).thenReturn(StatementExtractionResult.success(List.of(), "1234", null, null, null));

        assertThat(ingestOne().status()).isEqualTo("SUCCESS");

        verify(vault).saveIfMissing(userId, accountId, "other");
    }

    @Test
    void noPasswordAnywhereIsPasswordRequired() {
        when(vault.savedPasswords(userId)).thenReturn(List.of());
        when(parser.parse(bytes, null)).thenReturn(StatementExtractionResult.failure(LOCKED));

        FileIngestionResult.FileSummary file = ingestOne();

        assertThat(file.status()).isEqualTo("FAILED");
        assertThat(file.errorCode()).isEqualTo(FileIngestionResult.ErrorCode.PASSWORD_REQUIRED);
    }

    @Test
    void passwordsTriedAndAllWrongIsWrongPassword() {
        details.setStatementPassword("own");
        when(vault.savedPasswords(userId)).thenReturn(List.of("own", "x"));
        when(parser.parse(eq(bytes), nullable(String.class))).thenReturn(StatementExtractionResult.failure(LOCKED));

        assertThat(ingestOne().errorCode()).isEqualTo(FileIngestionResult.ErrorCode.WRONG_PASSWORD);
    }

    @Test
    void aTypedPasswordIsTriedBeforeTheAccountsOne() {
        details.setStatementPassword("own");
        when(parser.parse(bytes, "typed")).thenReturn(StatementExtractionResult.success(List.of(), "1234", null, null, null));

        FileIngestionResult r = service.ingest(accountId, List.of(new UploadedFile("a.pdf", "application/pdf", bytes)), null, "typed");

        assertThat(r.fileDetails().get(0).status()).isEqualTo("SUCCESS");
        verify(parser, never()).parse(bytes, "own");
    }

    @Test
    void anUnreadableFileIsUnreadableNotAPasswordProblem() {
        when(parser.parse(eq(bytes), nullable(String.class))).thenReturn(StatementExtractionResult.failure("ERROR: failed to read PDF."));

        assertThat(ingestOne().errorCode()).isEqualTo(FileIngestionResult.ErrorCode.UNREADABLE);
        verifyNoInteractions(vault);
    }
}
