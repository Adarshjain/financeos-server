package com.financeos.domain.ingestion;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.core.crypto.EncryptionService;
import com.financeos.core.exception.ApiStatusException;
import com.financeos.core.exception.ResourceNotFoundException;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.account.AccountType;
import com.financeos.domain.job.Job;
import com.financeos.domain.job.JobArtifact;
import com.financeos.domain.job.JobArtifactRepository;
import com.financeos.domain.job.JobRepository;
import com.financeos.domain.job.JobService;
import com.financeos.domain.job.JobType;
import com.financeos.domain.job.StagedFile;
import com.financeos.domain.job.handlers.StatementIngestPayload;
import com.financeos.domain.statement.Statement;
import com.financeos.domain.statement.StatementDeletionService;
import com.financeos.domain.statement.StatementRepository;
import com.financeos.statement.parser.StatementIdentity;
import com.financeos.statement.parser.StatementParseException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class StatementIntakeServiceTest {

    private static final String LOCKED = "ERROR: PDF is password-protected and the password is missing or wrong.";

    private JobRepository jobRepository;
    private JobService jobService;
    private JobArtifactRepository artifactRepository;
    private AccountRepository accountRepository;
    private StatementRepository statementRepository;
    private StatementDeletionService deletionService;
    private FileIngestionService ingestion;
    private StatementIdentifier identifier;
    private StatementPasswordVault vault;
    private EncryptionService encryption;
    private StatementIntakeService service;

    private final UUID userId = UUID.randomUUID();
    private final UUID accountId = UUID.randomUUID();
    private final UUID targetId = UUID.randomUUID();
    private final UUID jobId = UUID.randomUUID();
    private Job job;
    private JobArtifact artifact;

    @BeforeEach
    void setUp() {
        jobRepository = mock(JobRepository.class);
        jobService = mock(JobService.class);
        artifactRepository = mock(JobArtifactRepository.class);
        accountRepository = mock(AccountRepository.class);
        statementRepository = mock(StatementRepository.class);
        deletionService = mock(StatementDeletionService.class);
        ingestion = mock(FileIngestionService.class);
        identifier = mock(StatementIdentifier.class);
        vault = mock(StatementPasswordVault.class);
        encryption = mock(EncryptionService.class);
        service = new StatementIntakeService(jobRepository, jobService, artifactRepository, accountRepository, statementRepository,
                deletionService, ingestion, identifier, vault, encryption, new ObjectMapper());

        job = new Job();
        job.setId(jobId);
        job.setUserId(userId);
        job.setType(JobType.STATEMENT_INGEST);
        job.setPayload("{\"accountId\":\"" + accountId + "\"}");
        artifact = new JobArtifact();
        artifact.setId(UUID.randomUUID());
        artifact.setJobId(jobId);
        artifact.setFilename("s.pdf");
        artifact.setContentType("application/pdf");
        artifact.setData("locked".getBytes());

        when(jobRepository.findByIdAndUserId(jobId, userId)).thenReturn(Optional.of(job));
        when(jobService.getArtifacts(jobId)).thenReturn(List.of(artifact));
        when(accountRepository.findByIdAndUser_Id(accountId, userId)).thenReturn(Optional.of(account(accountId, AccountType.bank_account)));
        when(accountRepository.findByIdAndUser_Id(targetId, userId)).thenReturn(Optional.of(account(targetId, AccountType.credit_card)));
        when(encryption.encrypt(any())).thenAnswer(inv -> "enc:" + inv.getArgument(0));
        Job rerun = new Job();
        rerun.setId(UUID.randomUUID());
        when(jobService.enqueue(any(), any(), any(), any(), anyList(), any())).thenReturn(rerun);
    }

    private static Account account(UUID id, AccountType type) {
        Account a = new Account();
        a.setId(id);
        a.setType(type);
        return a;
    }

    // ---- password re-run ----

    @Test
    void rerunWithTheRightPasswordEnqueuesANewJobWithThePasswordEncryptedAndDropsTheOldFile() {
        when(identifier.identify(artifact.getData(), "pw")).thenReturn(new StatementIdentity("hdfc", "HDFC", "1234"));

        Job result = service.rerunWithPassword(userId, jobId, 0, "pw", false);

        assertThat(result).isNotNull();
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        ArgumentCaptor<List<StagedFile>> files = ArgumentCaptor.forClass(List.class);
        verify(jobService).enqueue(eq(userId), eq(JobType.STATEMENT_INGEST), any(), payload.capture(), files.capture(), any());
        StatementIngestPayload p = (StatementIngestPayload) payload.getValue();
        assertThat(p.accountId()).isEqualTo(accountId);
        assertThat(p.encryptedPassword()).isEqualTo("enc:pw");
        assertThat(files.getValue()).hasSize(1);
        verify(vault, never()).save(any(), any(), any());
        verify(artifactRepository).delete(artifact);
    }

    @Test
    void rerunWithSaveToAccountSavesThePasswordAndDoesNotCarryItInThePayload() {
        when(identifier.identify(artifact.getData(), "pw")).thenReturn(new StatementIdentity("hdfc", "HDFC", "1234"));

        service.rerunWithPassword(userId, jobId, 0, "pw", true);

        verify(vault).save(userId, accountId, "pw");
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(jobService).enqueue(any(), any(), any(), payload.capture(), anyList(), any());
        assertThat(((StatementIngestPayload) payload.getValue()).encryptedPassword()).isNull();
    }

    @Test
    void rerunWithAWrongPasswordIs422WrongPasswordAndChangesNothing() {
        when(identifier.identify(artifact.getData(), "bad")).thenThrow(new StatementParseException(LOCKED));

        assertThatThrownBy(() -> service.rerunWithPassword(userId, jobId, 0, "bad", true))
                .isInstanceOfSatisfying(ApiStatusException.class, e -> {
                    assertThat(e.getStatus().value()).isEqualTo(422);
                    assertThat(e.getCode()).isEqualTo("WRONG_PASSWORD");
                });
        verify(vault, never()).save(any(), any(), any());
        verify(jobService, never()).enqueue(any(), any(), any(), any(), anyList(), any());
        verify(artifactRepository, never()).delete(any());
    }

    @Test
    void rerunWhenTheFileIsNoLongerStoredIs409() {
        when(jobService.getArtifacts(jobId)).thenReturn(List.of());

        assertThatThrownBy(() -> service.rerunWithPassword(userId, jobId, 0, "pw", false))
                .isInstanceOfSatisfying(ApiStatusException.class, e -> {
                    assertThat(e.getStatus().value()).isEqualTo(409);
                    assertThat(e.getMessage()).isEqualTo("The file is no longer stored. Upload it again.");
                });
    }

    @Test
    void rerunOfAnotherUsersJobIs404() {
        UUID other = UUID.randomUUID();
        when(jobRepository.findByIdAndUserId(jobId, other)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.rerunWithPassword(other, jobId, 0, "pw", false)).isInstanceOf(ResourceNotFoundException.class);
        verifyNoInteractions(identifier, vault);
    }

    // ---- move ----

    private Statement statement(UUID stmtId, String sha) {
        Statement s = new Statement();
        s.setId(stmtId);
        s.setFileSha256(sha);
        return s;
    }

    private void stored(UUID stmtId) {
        when(statementRepository.findByIdAndAccount_Id(stmtId, accountId)).thenReturn(Optional.of(statement(stmtId, "abc")));
        when(artifactRepository.findByUserAndSha(userId, "abc")).thenReturn(List.of(artifact));
    }

    private static FileIngestionResult resultOf(FileIngestionResult.FileSummary f) {
        return new FileIngestionResult(1, f.created(), 0, List.of(f));
    }

    @Test
    void moveIngestsIntoTheTargetFirstThenDeletesTheSourceStatement() {
        UUID stmtId = UUID.randomUUID();
        stored(stmtId);
        FileIngestionResult expected = resultOf(new FileIngestionResult.FileSummary("s.pdf", "SUCCESS", 4, null, null, 4, 0));
        when(ingestion.ingest(eq(targetId), anyList(), any())).thenReturn(expected);

        FileIngestionResult r = service.moveStatement(userId, accountId, stmtId, targetId);

        assertThat(r).isSameAs(expected);
        InOrder order = inOrder(ingestion, deletionService);
        order.verify(ingestion).ingest(eq(targetId), anyList(), any());
        order.verify(deletionService).delete(accountId, stmtId);
    }

    @Test
    void moveWhenTheTargetIngestFailsKeepsTheSourceAndReturnsTheResult() {
        UUID stmtId = UUID.randomUUID();
        stored(stmtId);
        FileIngestionResult failed = resultOf(FileIngestionResult.FileSummary.failed("s.pdf", "bad", FileIngestionResult.ErrorCode.UNREADABLE));
        when(ingestion.ingest(eq(targetId), anyList(), any())).thenReturn(failed);

        assertThat(service.moveStatement(userId, accountId, stmtId, targetId)).isSameAs(failed);
        verifyNoInteractions(deletionService);
    }

    @Test
    void moveWhenTheTargetNeedsAPasswordIs422AndKeepsTheSource() {
        UUID stmtId = UUID.randomUUID();
        stored(stmtId);
        when(ingestion.ingest(eq(targetId), anyList(), any())).thenReturn(
                resultOf(FileIngestionResult.FileSummary.failed("s.pdf", "locked", FileIngestionResult.ErrorCode.PASSWORD_REQUIRED)));

        assertThatThrownBy(() -> service.moveStatement(userId, accountId, stmtId, targetId))
                .isInstanceOfSatisfying(ApiStatusException.class, e -> {
                    assertThat(e.getStatus().value()).isEqualTo(422);
                    assertThat(e.getCode()).isEqualTo("PASSWORD_REQUIRED");
                });
        verifyNoInteractions(deletionService);
    }

    @Test
    void moveWhenTheTargetFileIsSkippedKeepsTheSource() {
        UUID stmtId = UUID.randomUUID();
        stored(stmtId);
        when(ingestion.ingest(eq(targetId), anyList(), any())).thenReturn(
                resultOf(new FileIngestionResult.FileSummary("s.pdf", "SKIPPED", 0, "already imported")));

        service.moveStatement(userId, accountId, stmtId, targetId);

        verifyNoInteractions(deletionService);
    }

    @Test
    void moveWhenTheFileIsGoneIs409AndDeletesNothing() {
        UUID stmtId = UUID.randomUUID();
        when(statementRepository.findByIdAndAccount_Id(stmtId, accountId)).thenReturn(Optional.of(statement(stmtId, "abc")));
        when(artifactRepository.findByUserAndSha(userId, "abc")).thenReturn(List.of());

        assertThatThrownBy(() -> service.moveStatement(userId, accountId, stmtId, targetId))
                .isInstanceOfSatisfying(ApiStatusException.class, e -> {
                    assertThat(e.getStatus().value()).isEqualTo(409);
                    assertThat(e.getMessage()).isEqualTo("The file is no longer stored. Upload it again.");
                });
        verifyNoInteractions(deletionService);
        verifyNoInteractions(ingestion);
    }

    @Test
    void moveOfAStatementWithoutAFileHashIs409() {
        UUID stmtId = UUID.randomUUID();
        when(statementRepository.findByIdAndAccount_Id(stmtId, accountId)).thenReturn(Optional.of(statement(stmtId, null)));

        assertThatThrownBy(() -> service.moveStatement(userId, accountId, stmtId, targetId)).isInstanceOf(ApiStatusException.class);
        verifyNoInteractions(deletionService);
    }

    @Test
    void moveWithAnotherUsersTargetAccountIs404() {
        UUID foreign = UUID.randomUUID();
        UUID stmtId = UUID.randomUUID();
        when(accountRepository.findByIdAndUser_Id(foreign, userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.moveStatement(userId, accountId, stmtId, foreign)).isInstanceOf(ResourceNotFoundException.class);
        verifyNoInteractions(deletionService);
    }

    @Test
    void moveFromAnotherUsersAccountIs404() {
        UUID foreign = UUID.randomUUID();
        when(accountRepository.findByIdAndUser_Id(foreign, userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.moveStatement(userId, foreign, UUID.randomUUID(), targetId)).isInstanceOf(ResourceNotFoundException.class);
        verifyNoInteractions(deletionService);
    }

    @Test
    void moveOfAStatementThatIsNotOnTheSourceAccountIs404() {
        UUID stmtId = UUID.randomUUID();
        when(statementRepository.findByIdAndAccount_Id(stmtId, accountId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.moveStatement(userId, accountId, stmtId, targetId)).isInstanceOf(ResourceNotFoundException.class);
    }
}
