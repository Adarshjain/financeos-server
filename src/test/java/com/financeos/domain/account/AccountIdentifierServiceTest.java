package com.financeos.domain.account;

import com.financeos.core.exception.ValidationException;
import com.financeos.domain.job.Job;
import com.financeos.domain.job.JobService;
import com.financeos.domain.job.JobTrigger;
import com.financeos.domain.job.JobType;
import com.financeos.domain.user.User;
import com.financeos.gmail.domain.GmailConnection;
import com.financeos.gmail.domain.GmailProcessedMessage;
import com.financeos.gmail.domain.GmailProcessedMessageRepository;
import com.financeos.gmail.domain.GmailProcessedStatus;
import com.financeos.gmail.ingest.GmailIngestProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AccountIdentifierServiceTest {

    private AccountIdentifierRepository accountIdentifierRepository;
    private GmailProcessedMessageRepository processedMessageRepository;
    private JobService jobService;
    private GmailIngestProperties ingestProperties;
    private AccountIdentifierService service;

    private User testUser;
    private Account testAccount;

    @BeforeEach
    void setUp() {
        accountIdentifierRepository = mock(AccountIdentifierRepository.class);
        processedMessageRepository = mock(GmailProcessedMessageRepository.class);
        jobService = mock(JobService.class);
        ingestProperties = mock(GmailIngestProperties.class);
        when(ingestProperties.getDateWindowDays()).thenReturn(30);

        service = new AccountIdentifierService(
                accountIdentifierRepository,
                processedMessageRepository,
                jobService,
                ingestProperties
        );

        testUser = new User();
        testUser.setId(UUID.randomUUID());

        testAccount = new Account("Test Account", AccountType.bank_account);
        testAccount.setId(UUID.randomUUID());
        testAccount.setUser(testUser);
        testAccount.setIngestFromDate(LocalDate.of(2026, 1, 1));
    }

    @Test
    void normalizeAndValidate_stripsWhitespace() {
        assertThat(AccountIdentifier.normalize("  12 34 56  ")).isEqualTo("123456");

        assertThatThrownBy(() -> AccountIdentifier.validateNormalized("1"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("between 2 and 32 characters");

        assertThatThrownBy(() -> AccountIdentifier.validateNormalized("a".repeat(33)))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("between 2 and 32 characters");

        assertThatThrownBy(() -> AccountIdentifier.validateNormalized(""))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("cannot be blank");
    }

    @Test
    void createIdentifier_happyPath_createsAndReactivates() {
        when(accountIdentifierRepository.findByUserIdAndValue(testUser.getId(), "1234"))
                .thenReturn(Optional.empty());
        when(accountIdentifierRepository.save(any(AccountIdentifier.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        GmailConnection conn1 = new GmailConnection();
        conn1.setId(UUID.randomUUID());

        GmailProcessedMessage gpm1 = new GmailProcessedMessage();
        gpm1.setId(UUID.randomUUID());
        gpm1.setUser(testUser);
        gpm1.setConnection(conn1);
        gpm1.setExtractedLast4("1234");
        gpm1.setStatus(GmailProcessedStatus.UNRESOLVED_ACCOUNT);

        when(processedMessageRepository.findParkedForReactivation(
                eq(testUser.getId()), eq("1234"), anyCollection(), any(Instant.class)
        )).thenReturn(List.of(gpm1));

        Job mockJob = new Job();
        mockJob.setId(UUID.randomUUID());
        when(jobService.enqueue(eq(testUser.getId()), eq(JobType.GMAIL_SYNC), eq(JobTrigger.USER), any(), isNull(), eq(conn1.getId().toString())))
                .thenReturn(mockJob);

        AccountIdentifierService.CreationResult result = service.createOrUpdateIdentifier(
                testUser, testAccount, "  12 34  ", AccountIdentifierKind.CUSTOMER_ID
        );

        assertThat(result.identifier().getValue()).isEqualTo("1234");
        assertThat(result.identifier().getKind()).isEqualTo(AccountIdentifierKind.CUSTOMER_ID);
        assertThat(result.reactivatedCount()).isEqualTo(1);
        assertThat(result.jobIds()).containsExactly(mockJob.getId());
        assertThat(gpm1.getStatus()).isEqualTo(GmailProcessedStatus.DISCOVERED);
        assertThat(gpm1.getAttemptCount()).isEqualTo(0);

        verify(processedMessageRepository).saveAll(List.of(gpm1));
    }

    @Test
    void createIdentifier_idempotentSameAccount_reusesExisting() {
        AccountIdentifier existing = new AccountIdentifier(
                UUID.randomUUID(), testUser, testAccount, "1234", AccountIdentifierKind.CUSTOMER_ID, Instant.now()
        );
        when(accountIdentifierRepository.findByUserIdAndValue(testUser.getId(), "1234"))
                .thenReturn(Optional.of(existing));

        AccountIdentifierService.CreationResult result = service.createOrUpdateIdentifier(
                testUser, testAccount, "1234", AccountIdentifierKind.CUSTOMER_ID
        );

        assertThat(result.identifier()).isEqualTo(existing);
        verify(accountIdentifierRepository, never()).save(any());
    }

    @Test
    void createIdentifier_conflictWithOtherAccount_throwsValidationException() {
        Account otherAccount = new Account("Other Bank", AccountType.bank_account);
        otherAccount.setId(UUID.randomUUID());

        AccountIdentifier existingOnOther = new AccountIdentifier(
                UUID.randomUUID(), testUser, otherAccount, "1234", AccountIdentifierKind.CUSTOMER_ID, Instant.now()
        );
        when(accountIdentifierRepository.findByUserIdAndValue(testUser.getId(), "1234"))
                .thenReturn(Optional.of(existingOnOther));

        assertThatThrownBy(() -> service.createOrUpdateIdentifier(
                testUser, testAccount, "1234", AccountIdentifierKind.CUSTOMER_ID
        ))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("already assigned to account 'Other Bank'");
    }

    @Test
    void createIdentifier_nullIngestFromDate_skipsReactivation() {
        testAccount.setIngestFromDate(null);
        when(accountIdentifierRepository.findByUserIdAndValue(testUser.getId(), "1234"))
                .thenReturn(Optional.empty());
        when(accountIdentifierRepository.save(any(AccountIdentifier.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        AccountIdentifierService.CreationResult result = service.createOrUpdateIdentifier(
                testUser, testAccount, "1234", AccountIdentifierKind.OTHER
        );

        assertThat(result.reactivatedCount()).isEqualTo(0);
        assertThat(result.jobIds()).isEmpty();
        verify(processedMessageRepository, never()).findParkedForReactivation(any(), any(), any(), any());
    }

    @Test
    void deleteIdentifier_deletesHardWithoutReactivation() {
        UUID identifierId = UUID.randomUUID();
        AccountIdentifier identifier = new AccountIdentifier(
                identifierId, testUser, testAccount, "1234", AccountIdentifierKind.CUSTOMER_ID, Instant.now()
        );

        when(accountIdentifierRepository.findByIdAndAccountId(identifierId, testAccount.getId()))
                .thenReturn(Optional.of(identifier));

        service.deleteIdentifier(testUser.getId(), testAccount.getId(), identifierId);

        verify(accountIdentifierRepository).delete(identifier);
        verifyNoInteractions(processedMessageRepository);
    }
}
