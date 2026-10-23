package com.financeos.api.job;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.core.exception.ResourceNotFoundException;
import com.financeos.core.exception.ValidationException;
import com.financeos.core.security.UserContext;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.job.Job;
import com.financeos.domain.job.JobRepository;
import com.financeos.domain.job.JobService;
import com.financeos.domain.job.JobType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class JobControllerTest {

    private JobRepository jobRepository;
    private JobService jobService;
    private ObjectMapper objectMapper;
    private AccountRepository accountRepository;
    private JobController jobController;

    @BeforeEach
    void setUp() {
        jobRepository = mock(JobRepository.class);
        jobService = mock(JobService.class);
        objectMapper = new ObjectMapper();
        accountRepository = mock(AccountRepository.class);
        jobController = new JobController(jobRepository, jobService, objectMapper, accountRepository);
    }

    @Test
    void getJobs_throwsValidationException_whenStatusIsInvalid() {
        UUID userId = UUID.randomUUID();
        UserContext.setCurrentUserId(userId);

        assertThatThrownBy(() -> jobController.getJobs("INVALID_STATUS", null, null, null))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Invalid status: INVALID_STATUS");

        UserContext.clear();
    }

    @Test
    void getJobs_throwsValidationException_whenTypeIsInvalid() {
        UUID userId = UUID.randomUUID();
        UserContext.setCurrentUserId(userId);

        assertThatThrownBy(() -> jobController.getJobs(null, "BOGUS_TYPE", null, null))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Invalid type: BOGUS_TYPE");

        UserContext.clear();
    }

    @Test
    void getJobs_parsesMultiTypeString() {
        UUID userId = UUID.randomUUID();
        UserContext.setCurrentUserId(userId);

        when(jobRepository.findByUserIdAndTypeIn(eq(userId), eq(List.of(JobType.PRICE_REFRESH, JobType.GMAIL_SYNC)), any()))
                .thenReturn(org.springframework.data.domain.Page.empty());

        jobController.getJobs(null, "PRICE_REFRESH, GMAIL_SYNC", null, null);

        verify(jobRepository).findByUserIdAndTypeIn(eq(userId), eq(List.of(JobType.PRICE_REFRESH, JobType.GMAIL_SYNC)), any());
        UserContext.clear();
    }

    @Test
    void getJob_throwsResourceNotFoundException_whenJobNotOwnedByUser() {
        UUID userIdUserA = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        UserContext.setCurrentUserId(userIdUserA);

        when(jobRepository.findByIdAndUserId(jobId, userIdUserA)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> jobController.getJobById(jobId))
                .isInstanceOf(ResourceNotFoundException.class);

        UserContext.clear();
    }

    @Test
    void cancelJob_throwsResourceNotFoundException_whenForeignJob() {
        UUID userId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        UserContext.setCurrentUserId(userId);

        when(jobService.requestCancel(userId, jobId)).thenThrow(new ResourceNotFoundException("Job", jobId));

        assertThatThrownBy(() -> jobController.cancelJob(jobId))
                .isInstanceOf(ResourceNotFoundException.class);

        UserContext.clear();
    }

    @Test
    void getJobs_withSince_usesCreatedAfterQueryCombinedWithType() {
        UUID userId = UUID.randomUUID();
        UserContext.setCurrentUserId(userId);
        java.time.Instant since = java.time.Instant.parse("2026-09-23T00:00:00Z");
        when(jobRepository.findByUserIdAndTypeInAndCreatedAtGreaterThanEqual(eq(userId), eq(List.of(JobType.STATEMENT_INGEST)), eq(since), any()))
                .thenReturn(org.springframework.data.domain.Page.empty());

        jobController.getJobs(null, "STATEMENT_INGEST", since, null);

        verify(jobRepository).findByUserIdAndTypeInAndCreatedAtGreaterThanEqual(eq(userId), eq(List.of(JobType.STATEMENT_INGEST)), eq(since), any());
        UserContext.clear();
    }

    @Test
    void getJobs_withSinceOnly_usesCreatedAfterQuery() {
        UUID userId = UUID.randomUUID();
        UserContext.setCurrentUserId(userId);
        java.time.Instant since = java.time.Instant.parse("2026-09-23T00:00:00Z");
        when(jobRepository.findByUserIdAndCreatedAtGreaterThanEqual(eq(userId), eq(since), any()))
                .thenReturn(org.springframework.data.domain.Page.empty());

        jobController.getJobs(null, null, since, null);

        verify(jobRepository).findByUserIdAndCreatedAtGreaterThanEqual(eq(userId), eq(since), any());
        UserContext.clear();
    }

    @Test
    void getJobs_resolvesAccountForAllRowsInOneLookup() {
        UUID userId = UUID.randomUUID();
        UserContext.setCurrentUserId(userId);
        UUID accountId = UUID.randomUUID();
        UUID foreignAccountId = UUID.randomUUID();
        com.financeos.domain.user.User owner = new com.financeos.domain.user.User();
        owner.setId(userId);
        com.financeos.domain.user.User other = new com.financeos.domain.user.User();
        other.setId(UUID.randomUUID());
        Account account = new Account();
        account.setId(accountId);
        account.setName("HDFC Savings");
        account.setUser(owner);
        Account foreign = new Account();
        foreign.setId(foreignAccountId);
        foreign.setName("Not mine");
        foreign.setUser(other);

        Job a = ingestJob("{\"accountId\":\"" + accountId + "\"}");
        Job b = ingestJob("{\"accountId\":\"" + accountId + "\"}");
        Job c = ingestJob("{\"accountId\":\"" + foreignAccountId + "\"}");
        Job d = new Job();
        d.setId(UUID.randomUUID());
        d.setType(JobType.PRICE_REFRESH);
        d.setCreatedAt(java.time.Instant.now());

        when(jobRepository.findByUserId(eq(userId), any()))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(a, b, c, d)));
        when(accountRepository.findAllById(any())).thenReturn(List.of(account, foreign));

        var page = jobController.getJobs(null, null, null, null).getBody();

        assertThat(page.getContent().get(0).accountId()).isEqualTo(accountId);
        assertThat(page.getContent().get(0).accountName()).isEqualTo("HDFC Savings");
        assertThat(page.getContent().get(1).accountName()).isEqualTo("HDFC Savings");
        assertThat(page.getContent().get(2).accountId()).isNull();
        assertThat(page.getContent().get(2).accountName()).isNull();
        assertThat(page.getContent().get(3).accountId()).isNull();
        verify(accountRepository, times(1)).findAllById(any());
        UserContext.clear();
    }

    @Test
    void getJobById_includesAccount() {
        UUID userId = UUID.randomUUID();
        UserContext.setCurrentUserId(userId);
        UUID accountId = UUID.randomUUID();
        com.financeos.domain.user.User owner = new com.financeos.domain.user.User();
        owner.setId(userId);
        Account account = new Account();
        account.setId(accountId);
        account.setName("ICICI Card");
        account.setUser(owner);
        Job job = ingestJob("{\"accountId\":\"" + accountId + "\"}");
        when(jobRepository.findByIdAndUserId(job.getId(), userId)).thenReturn(Optional.of(job));
        when(accountRepository.findAllById(any())).thenReturn(List.of(account));

        var body = jobController.getJobById(job.getId()).getBody();

        assertThat(body.accountId()).isEqualTo(accountId);
        assertThat(body.accountName()).isEqualTo("ICICI Card");
        UserContext.clear();
    }

    private static Job ingestJob(String payload) {
        Job j = new Job();
        j.setId(UUID.randomUUID());
        j.setType(JobType.STATEMENT_INGEST);
        j.setPayload(payload);
        j.setCreatedAt(java.time.Instant.now());
        return j;
    }
}
