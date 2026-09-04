package com.financeos.api.gmail;

import com.financeos.api.gmail.dto.CleanupResultResponse;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountRepository;
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
                ingestProperties
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
}
