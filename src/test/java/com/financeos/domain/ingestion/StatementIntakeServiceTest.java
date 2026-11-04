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
import com.financeos.domain.statement.StatementMoveService;
import com.financeos.domain.statement.StatementRepository;
import com.financeos.statement.parser.StatementIdentity;
import com.financeos.statement.parser.StatementParseException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

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
    private StatementMoveService moveService;
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
        moveService = mock(StatementMoveService.class);
        identifier = mock(StatementIdentifier.class);
        vault = mock(StatementPasswordVault.class);
        encryption = mock(EncryptionService.class);
        service = new StatementIntakeService(jobRepository, jobService, artifactRepository, accountRepository, statementRepository,
                moveService, identifier, vault, encryption, new ObjectMapper());

        job = new Job();
        job.setId(jobId);
        job.setUserId(userId);
        job.setType(JobType.STATEMENT_INGEST);
        job.setStatus(com.financeos.domain.job.JobStatus.FAILED);
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
        when(accountRepository.findByIdAndUser_Id(targetId, userId)).thenReturn(Optional.of(account(targetId, AccountType.bank_account)));
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
    }

    @Test
    void moveHandsTheStatementToTheMoveServiceAndReportsTheMovedRows() {
        UUID stmtId = UUID.randomUUID();
        stored(stmtId);
        when(moveService.move(userId, accountId, targetId, stmtId)).thenReturn(new StatementMoveService.Moved(4, 1));

        FileIngestionResult r = service.moveStatement(userId, accountId, stmtId, targetId);

        assertThat(r.totalCreated()).isEqualTo(4);
        assertThat(r.totalDuplicatesFound()).isEqualTo(1);
        assertThat(r.fileDetails()).hasSize(1);
        assertThat(r.fileDetails().get(0).status()).isEqualTo("SUCCESS");
    }

    @Test
    void moveWorksForAStatementWithNoStoredFileAtAll() {
        UUID stmtId = UUID.randomUUID();
        when(statementRepository.findByIdAndAccount_Id(stmtId, accountId)).thenReturn(Optional.of(statement(stmtId, null)));
        when(moveService.move(userId, accountId, targetId, stmtId)).thenReturn(new StatementMoveService.Moved(2, 0));

        assertThat(service.moveStatement(userId, accountId, stmtId, targetId).totalCreated()).isEqualTo(2);
        verifyNoInteractions(artifactRepository);
    }

    @Test
    void moveToTheSameAccountIsRejected() {
        UUID stmtId = UUID.randomUUID();
        when(statementRepository.findByIdAndAccount_Id(stmtId, accountId)).thenReturn(Optional.of(statement(stmtId, "abc")));

        assertThatThrownBy(() -> service.moveStatement(userId, accountId, stmtId, accountId))
                .isInstanceOf(com.financeos.core.exception.ValidationException.class).hasMessage("Pick a different account.");
        verifyNoInteractions(moveService);
    }

    @Test
    void moveToAnAccountOfAnotherTypeIsRejected() {
        UUID stmtId = UUID.randomUUID();
        stored(stmtId);
        when(accountRepository.findByIdAndUser_Id(targetId, userId)).thenReturn(Optional.of(account(targetId, AccountType.credit_card)));

        assertThatThrownBy(() -> service.moveStatement(userId, accountId, stmtId, targetId))
                .isInstanceOf(com.financeos.core.exception.ValidationException.class).hasMessage("Pick an account of the same type.");
        verifyNoInteractions(moveService);
    }

    @Test
    void moveWhenTheTargetHasTheSameFileIs409() {
        UUID stmtId = UUID.randomUUID();
        stored(stmtId);
        when(statementRepository.existsByAccountIdAndFileSha256(targetId, "abc")).thenReturn(true);

        assertThatThrownBy(() -> service.moveStatement(userId, accountId, stmtId, targetId))
                .isInstanceOfSatisfying(ApiStatusException.class, e -> {
                    assertThat(e.getStatus().value()).isEqualTo(409);
                    assertThat(e.getCode()).isEqualTo("STATEMENT_EXISTS");
                });
        verifyNoInteractions(moveService);
    }

    @Test
    void moveWhenTheTargetHasTheSamePeriodIs409() {
        UUID stmtId = UUID.randomUUID();
        Statement st = statement(stmtId, "abc");
        st.setPeriodStart(java.time.LocalDate.of(2026, 1, 1));
        st.setPeriodEnd(java.time.LocalDate.of(2026, 1, 31));
        when(statementRepository.findByIdAndAccount_Id(stmtId, accountId)).thenReturn(Optional.of(st));
        when(statementRepository.existsByAccountIdAndPeriodStartAndPeriodEnd(targetId, st.getPeriodStart(), st.getPeriodEnd())).thenReturn(true);

        assertThatThrownBy(() -> service.moveStatement(userId, accountId, stmtId, targetId))
                .isInstanceOfSatisfying(ApiStatusException.class, e -> assertThat(e.getCode()).isEqualTo("STATEMENT_EXISTS"));
        verifyNoInteractions(moveService);
    }

    @Test
    void rerunPicksTheFileByItsIndexNotItsPosition() {
        JobArtifact second = new JobArtifact();
        second.setFileIndex(1);
        second.setFilename("b.pdf");
        second.setData("b".getBytes());
        artifact.setFileIndex(0);
        when(jobService.getArtifacts(jobId)).thenReturn(List.of(second)); // file 0 was already used up
        when(identifier.identify(second.getData(), "pw")).thenReturn(new StatementIdentity("hdfc", "HDFC", "1234"));

        service.rerunWithPassword(userId, jobId, 1, "pw", false);
        verify(artifactRepository).delete(second);

        assertThatThrownBy(() -> service.rerunWithPassword(userId, jobId, 0, "pw", false))
                .isInstanceOfSatisfying(ApiStatusException.class, e -> assertThat(e.getStatus().value()).isEqualTo(409));
    }

    @Test
    void rerunOfAJobThatIsStillRunningIs409() {
        job.setStatus(com.financeos.domain.job.JobStatus.RUNNING);

        assertThatThrownBy(() -> service.rerunWithPassword(userId, jobId, 0, "pw", false))
                .isInstanceOfSatisfying(ApiStatusException.class, e -> assertThat(e.getStatus().value()).isEqualTo(409));
        verifyNoInteractions(identifier);
    }

    @Test
    void moveWithAnotherUsersTargetAccountIs404() {
        UUID foreign = UUID.randomUUID();
        UUID stmtId = UUID.randomUUID();
        when(accountRepository.findByIdAndUser_Id(foreign, userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.moveStatement(userId, accountId, stmtId, foreign)).isInstanceOf(ResourceNotFoundException.class);
        verifyNoInteractions(moveService);
    }

    @Test
    void moveFromAnotherUsersAccountIs404() {
        UUID foreign = UUID.randomUUID();
        when(accountRepository.findByIdAndUser_Id(foreign, userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.moveStatement(userId, foreign, UUID.randomUUID(), targetId)).isInstanceOf(ResourceNotFoundException.class);
        verifyNoInteractions(moveService);
    }

    @Test
    void moveOfAStatementThatIsNotOnTheSourceAccountIs404() {
        UUID stmtId = UUID.randomUUID();
        when(statementRepository.findByIdAndAccount_Id(stmtId, accountId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.moveStatement(userId, accountId, stmtId, targetId)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void moveNamesAnUploadByItsFileAndAGmailStatementNeutrally() {
        UUID up = UUID.randomUUID();
        Statement upload = statement(up, "abc");
        upload.setSource(com.financeos.domain.statement.StatementSource.file_upload);
        upload.setSourceRef("jan.pdf");
        when(statementRepository.findByIdAndAccount_Id(up, accountId)).thenReturn(Optional.of(upload));
        when(moveService.move(userId, accountId, targetId, up)).thenReturn(new StatementMoveService.Moved(1, 0));
        assertThat(service.moveStatement(userId, accountId, up, targetId).fileDetails().get(0).filename()).isEqualTo("jan.pdf");

        UUID gm = UUID.randomUUID();
        Statement mail = statement(gm, null);
        mail.setSource(com.financeos.domain.statement.StatementSource.gmail);
        mail.setSourceRef("18f3a9message");
        when(statementRepository.findByIdAndAccount_Id(gm, accountId)).thenReturn(Optional.of(mail));
        when(moveService.move(userId, accountId, targetId, gm)).thenReturn(new StatementMoveService.Moved(1, 0));
        assertThat(service.moveStatement(userId, accountId, gm, targetId).fileDetails().get(0).filename()).isEqualTo("Statement");
    }
}
