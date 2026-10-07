package com.financeos.api.ingestion;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.financeos.api.job.dto.EnqueueResponse;
import com.financeos.core.exception.ValidationException;
import com.financeos.core.security.UserContext;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.account.AccountType;
import com.financeos.domain.job.Job;
import com.financeos.domain.job.JobService;
import com.financeos.domain.job.JobTrigger;
import com.financeos.domain.job.JobType;
import com.financeos.domain.user.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.util.Optional;
import java.util.UUID;

/**
 * Statement upload is a bank/credit-card capability. Brokers (broker imports) and generic
 * Wallet/Cash accounts (manual) are rejected before a job is enqueued, mirroring the account
 * filter the upload form already applies.
 */
class FileUploadControllerTest {

    private AccountRepository accountRepository;
    private JobService jobService;
    private FileUploadController controller;

    private final UUID userId = UUID.randomUUID();
    private final MultipartFile[] files = {
            new MockMultipartFile("files", "statement.pdf", "application/pdf", new byte[] {1, 2, 3})
    };

    @BeforeEach
    void setUp() {
        accountRepository = mock(AccountRepository.class);
        jobService = mock(JobService.class);
        controller = new FileUploadController(accountRepository, jobService);
        UserContext.setCurrentUserId(userId);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private Account ownedAccount(AccountType type) {
        User user = new User();
        user.setId(userId);
        Account account = new Account("Acct", type);
        account.setId(UUID.randomUUID());
        account.setUser(user);
        when(accountRepository.findById(account.getId())).thenReturn(Optional.of(account));
        return account;
    }

    @Test
    void brokerAccount_isRejected_andNoJobIsEnqueued() {
        Account broker = ownedAccount(AccountType.broker);

        ValidationException ex = assertThrows(ValidationException.class,
                () -> controller.ingestFiles(broker.getId(), files));

        assertEquals(FileUploadController.STATEMENT_TYPE_MESSAGE, ex.getMessage());
        verifyNoInteractions(jobService);
    }

    @Test
    void genericAccount_isRejected_andNoJobIsEnqueued() {
        Account wallet = ownedAccount(AccountType.generic);

        ValidationException ex = assertThrows(ValidationException.class,
                () -> controller.ingestFiles(wallet.getId(), files));

        assertEquals(FileUploadController.STATEMENT_TYPE_MESSAGE, ex.getMessage());
        verifyNoInteractions(jobService);
    }

    @Test
    void bankAccount_enqueuesStatementIngestJob() {
        Account bank = ownedAccount(AccountType.bank_account);
        Job job = mock(Job.class);
        UUID jobId = UUID.randomUUID();
        when(job.getId()).thenReturn(jobId);
        when(jobService.enqueue(eq(userId), eq(JobType.STATEMENT_INGEST), eq(JobTrigger.USER), any(), any(), any()))
                .thenReturn(job);

        ResponseEntity<EnqueueResponse> response = controller.ingestFiles(bank.getId(), files);

        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        assertEquals(jobId, response.getBody().jobId());
    }

    @Test
    void creditCardAccount_enqueuesStatementIngestJob() {
        Account card = ownedAccount(AccountType.credit_card);
        Job job = mock(Job.class);
        when(job.getId()).thenReturn(UUID.randomUUID());
        when(jobService.enqueue(eq(userId), eq(JobType.STATEMENT_INGEST), eq(JobTrigger.USER), any(), any(), any()))
                .thenReturn(job);

        ResponseEntity<EnqueueResponse> response = controller.ingestFiles(card.getId(), files);

        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        verify(jobService).enqueue(eq(userId), eq(JobType.STATEMENT_INGEST), eq(JobTrigger.USER), any(), any(), any());
    }

    @Test
    void ownershipIsCheckedBeforeType_foreignBrokerGetsPermissionError() {
        User other = new User();
        other.setId(UUID.randomUUID());
        Account foreign = new Account("Theirs", AccountType.broker);
        foreign.setId(UUID.randomUUID());
        foreign.setUser(other);
        when(accountRepository.findById(foreign.getId())).thenReturn(Optional.of(foreign));

        ValidationException ex = assertThrows(ValidationException.class,
                () -> controller.ingestFiles(foreign.getId(), files));

        assertNotEquals(FileUploadController.STATEMENT_TYPE_MESSAGE, ex.getMessage());
        verifyNoInteractions(jobService);
    }
}
