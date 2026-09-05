package com.financeos.api.gmail;

import com.financeos.api.gmail.dto.AssignAttentionRequest;
import com.financeos.api.gmail.dto.AssignAttentionResponse;
import com.financeos.api.gmail.dto.CleanupResultResponse;
import com.financeos.core.exception.ValidationException;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountIdentifier;
import com.financeos.domain.account.AccountIdentifierKind;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.account.AccountType;
import com.financeos.domain.job.JobService;
import com.financeos.domain.transaction.Transaction;
import com.financeos.domain.transaction.TransactionRepository;
import com.financeos.domain.user.AuthService;
import com.financeos.domain.user.User;
import com.financeos.gmail.domain.*;
import com.financeos.gmail.ingest.GmailIngestProperties;
import com.financeos.gmail.ingest.GmailIngestionService;
import com.financeos.gmail.ingest.SenderAllowlistService;
import com.financeos.gmail.oauth.GmailOAuthService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class GmailControllerTest {

    private GmailOAuthService oauthService;
    private AuthService authService;
    private GmailIngestionService gmailIngestionService;
    private SenderAllowlistService senderAllowlistService;
    private GmailConnectionRepository connectionRepository;
    private GmailSyncCursorRepository syncCursorRepository;
    private GmailProcessedMessageRepository processedMessageRepository;
    private GmailBackfillDemandRepository backfillDemandRepository;
    private AccountRepository accountRepository;
    private TransactionRepository transactionRepository;
    private JobService jobService;
    private GmailIngestProperties ingestProperties;
    private com.financeos.domain.account.AccountIdentifierService accountIdentifierService;

    private GmailController controller;
    private User currentUser;

    @BeforeEach
    void setUp() {
        oauthService = mock(GmailOAuthService.class);
        authService = mock(AuthService.class);
        gmailIngestionService = mock(GmailIngestionService.class);
        senderAllowlistService = mock(SenderAllowlistService.class);
        connectionRepository = mock(GmailConnectionRepository.class);
        syncCursorRepository = mock(GmailSyncCursorRepository.class);
        processedMessageRepository = mock(GmailProcessedMessageRepository.class);
        backfillDemandRepository = mock(GmailBackfillDemandRepository.class);
        accountRepository = mock(AccountRepository.class);
        transactionRepository = mock(TransactionRepository.class);
        jobService = mock(JobService.class);
        ingestProperties = mock(GmailIngestProperties.class);
        accountIdentifierService = mock(com.financeos.domain.account.AccountIdentifierService.class);

        controller = new GmailController(
                oauthService,
                authService,
                gmailIngestionService,
                senderAllowlistService,
                connectionRepository,
                syncCursorRepository,
                processedMessageRepository,
                backfillDemandRepository,
                accountRepository,
                transactionRepository,
                jobService,
                ingestProperties,
                accountIdentifierService
        );

        currentUser = new User();
        currentUser.setId(UUID.randomUUID());
        when(authService.getCurrentUser()).thenReturn(currentUser);
    }

    @Test
    void testPerformCleanup_StampsLedgerRowsAsCleanedUpAndNullsTransaction() {
        UUID accountId = UUID.randomUUID();
        LocalDate before = LocalDate.of(2026, 8, 1);

        Account account = new Account();
        account.setId(accountId);
        account.setUser(currentUser);
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));

        Transaction txn = new Transaction();
        txn.setId(UUID.randomUUID());
        txn.setUser(currentUser);
        txn.setAccount(account);
        txn.setDate(LocalDate.of(2026, 7, 15));

        when(transactionRepository.findUnreconciledAlertsBeforeDate(accountId, currentUser.getId(), before))
                .thenReturn(List.of(txn));

        GmailProcessedMessage gpm = new GmailProcessedMessage();
        gpm.setId(UUID.randomUUID());
        gpm.setTransaction(txn);
        gpm.setStatus(GmailProcessedStatus.CREATED);

        when(processedMessageRepository.findByTransactionId(txn.getId()))
                .thenReturn(Optional.of(gpm));

        ResponseEntity<CleanupResultResponse> response = controller.performCleanup(accountId, before);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().deletedCount()).isEqualTo(1);

        assertThat(gpm.getStatus()).isEqualTo(GmailProcessedStatus.CLEANED_UP);
        assertThat(gpm.getTransaction()).isNull();

        verify(processedMessageRepository).save(gpm);
        verify(transactionRepository).delete(txn);
    }

    @Test
    void testAssignAttentionItem_happyPath_createsAliasAndReactivates() {
        UUID ledgerId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        UUID identifierId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();

        Account account = new Account("HDFC Bank", AccountType.bank_account);
        account.setId(accountId);
        account.setUser(currentUser);

        GmailProcessedMessage gpm = new GmailProcessedMessage();
        gpm.setId(ledgerId);
        gpm.setUser(currentUser);
        gpm.setStatus(GmailProcessedStatus.UNRESOLVED_ACCOUNT);
        gpm.setExtractedLast4("1234");

        when(processedMessageRepository.findById(ledgerId)).thenReturn(Optional.of(gpm));
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));

        AccountIdentifier identifier = new AccountIdentifier(
                identifierId, currentUser, account, "1234", AccountIdentifierKind.CUSTOMER_ID, Instant.now()
        );
        when(accountIdentifierService.createOrUpdateIdentifier(
                eq(currentUser), eq(account), eq("1234"), eq(AccountIdentifierKind.CUSTOMER_ID)
        )).thenReturn(new com.financeos.domain.account.AccountIdentifierService.CreationResult(
                identifier, 2, List.of(jobId)
        ));

        AssignAttentionRequest request = new AssignAttentionRequest(accountId, AccountIdentifierKind.CUSTOMER_ID);
        ResponseEntity<AssignAttentionResponse> response = controller.assignAttentionItem(ledgerId, request);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().identifierId()).isEqualTo(identifierId);
        assertThat(response.getBody().reactivatedCount()).isEqualTo(2);
        assertThat(response.getBody().jobIds()).containsExactly(jobId);
    }

    @Test
    void testAssignAttentionItem_wrongStatus_throwsValidationException() {
        UUID ledgerId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();

        GmailProcessedMessage gpm = new GmailProcessedMessage();
        gpm.setId(ledgerId);
        gpm.setUser(currentUser);
        gpm.setStatus(GmailProcessedStatus.FAILED_PERMANENT);
        gpm.setExtractedLast4("1234");

        when(processedMessageRepository.findById(ledgerId)).thenReturn(Optional.of(gpm));

        AssignAttentionRequest request = new AssignAttentionRequest(accountId, AccountIdentifierKind.CUSTOMER_ID);
        org.junit.jupiter.api.Assertions.assertThrows(
                ValidationException.class,
                () -> controller.assignAttentionItem(ledgerId, request)
        );
    }

    @Test
    void testAssignAttentionItem_nullExtractedLast4_throwsValidationException() {
        UUID ledgerId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();

        GmailProcessedMessage gpm = new GmailProcessedMessage();
        gpm.setId(ledgerId);
        gpm.setUser(currentUser);
        gpm.setStatus(GmailProcessedStatus.UNRESOLVED_ACCOUNT);
        gpm.setExtractedLast4(null);

        when(processedMessageRepository.findById(ledgerId)).thenReturn(Optional.of(gpm));

        AssignAttentionRequest request = new AssignAttentionRequest(accountId, AccountIdentifierKind.CUSTOMER_ID);
        org.junit.jupiter.api.Assertions.assertThrows(
                ValidationException.class,
                () -> controller.assignAttentionItem(ledgerId, request)
        );
    }

    @Test
    void testAssignAttentionItem_otherUserMessage_throwsValidationException() {
        UUID ledgerId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();

        User otherUser = new User();
        otherUser.setId(UUID.randomUUID());

        GmailProcessedMessage gpm = new GmailProcessedMessage();
        gpm.setId(ledgerId);
        gpm.setUser(otherUser);
        gpm.setStatus(GmailProcessedStatus.UNRESOLVED_ACCOUNT);
        gpm.setExtractedLast4("1234");

        when(processedMessageRepository.findById(ledgerId)).thenReturn(Optional.of(gpm));

        AssignAttentionRequest request = new AssignAttentionRequest(accountId, AccountIdentifierKind.CUSTOMER_ID);
        org.junit.jupiter.api.Assertions.assertThrows(
                ValidationException.class,
                () -> controller.assignAttentionItem(ledgerId, request)
        );
    }
}
