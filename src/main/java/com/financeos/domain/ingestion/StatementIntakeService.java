package com.financeos.domain.ingestion;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.core.crypto.EncryptionService;
import com.financeos.core.exception.ApiStatusException;
import com.financeos.core.exception.ResourceNotFoundException;
import com.financeos.core.exception.ValidationException;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.ingestion.held.HeldUploadService;
import com.financeos.domain.job.Job;
import com.financeos.domain.job.JobArtifact;
import com.financeos.domain.job.JobArtifactRepository;
import com.financeos.domain.job.JobRepository;
import com.financeos.domain.job.JobService;
import com.financeos.domain.job.JobTrigger;
import com.financeos.domain.job.JobType;
import com.financeos.domain.job.StagedFile;
import com.financeos.domain.job.handlers.StatementIngestPayload;
import com.financeos.domain.statement.Statement;
import com.financeos.domain.statement.StatementDeletionService;
import com.financeos.domain.statement.StatementRepository;
import com.financeos.statement.parser.StatementParseException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/** Two ways to fix a statement import after the fact: re-run a locked stored file with a password, and move an ingested statement to another account. */
@Service
public class StatementIntakeService {

    private final JobRepository jobRepository;
    private final JobService jobService;
    private final JobArtifactRepository artifactRepository;
    private final AccountRepository accountRepository;
    private final StatementRepository statementRepository;
    private final StatementDeletionService deletionService;
    private final FileIngestionService ingestionService;
    private final StatementIdentifier identifier;
    private final StatementPasswordVault passwordVault;
    private final EncryptionService encryptionService;
    private final ObjectMapper objectMapper;

    public StatementIntakeService(JobRepository jobRepository,
                                  JobService jobService,
                                  JobArtifactRepository artifactRepository,
                                  AccountRepository accountRepository,
                                  StatementRepository statementRepository,
                                  StatementDeletionService deletionService,
                                  FileIngestionService ingestionService,
                                  StatementIdentifier identifier,
                                  StatementPasswordVault passwordVault,
                                  EncryptionService encryptionService,
                                  ObjectMapper objectMapper) {
        this.jobRepository = jobRepository;
        this.jobService = jobService;
        this.artifactRepository = artifactRepository;
        this.accountRepository = accountRepository;
        this.statementRepository = statementRepository;
        this.deletionService = deletionService;
        this.ingestionService = ingestionService;
        this.identifier = identifier;
        this.passwordVault = passwordVault;
        this.encryptionService = encryptionService;
        this.objectMapper = objectMapper;
    }

    /**
     * Re-runs one stored file of a statement-ingest job on the job's account with a password the user typed.
     * 409 when the file is no longer stored, 422 WRONG_PASSWORD when it still does not open. Returns the new job.
     */
    public Job rerunWithPassword(UUID userId, UUID jobId, int fileIndex, String password, boolean saveToAccount) {
        Job job = jobRepository.findByIdAndUserId(jobId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Job", jobId));
        if (job.getType() != JobType.STATEMENT_INGEST) {
            throw new ValidationException("Only statement imports have files.");
        }
        UUID accountId = accountIdOf(job);
        if (accountId == null) {
            throw new ValidationException("This import has no account yet. Pick an account for the held file instead.");
        }
        Account account = accountRepository.findByIdAndUser_Id(accountId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Account", accountId));
        List<JobArtifact> artifacts = jobService.getArtifacts(jobId);
        JobArtifact artifact = fileIndex < 0 || fileIndex >= artifacts.size() ? null : artifacts.get(fileIndex);
        if (artifact == null) {
            throw new ApiStatusException(HttpStatus.CONFLICT, "FILE_EXPIRED", HeldUploadService.FILE_GONE_MESSAGE);
        }
        if (password == null || password.isBlank()) {
            throw new ValidationException("Enter the password.");
        }
        try {
            identifier.identify(artifact.getData(), password);
        } catch (StatementParseException e) {
            if (e.isPasswordProblem()) {
                throw new ApiStatusException(HttpStatus.UNPROCESSABLE_ENTITY, FileIngestionResult.ErrorCode.WRONG_PASSWORD.name(),
                        "That password does not open this file.");
            }
            throw new ApiStatusException(HttpStatus.UNPROCESSABLE_ENTITY, FileIngestionResult.ErrorCode.UNREADABLE.name(), e.getMessage());
        }
        if (saveToAccount) {
            passwordVault.save(userId, account.getId(), password);
        }
        Job rerun = jobService.enqueue(userId, JobType.STATEMENT_INGEST, JobTrigger.USER,
                new StatementIngestPayload(accountId, saveToAccount ? null : encryptionService.encrypt(password)),
                List.of(new StagedFile(artifact.getFilename(), artifact.getContentType(), artifact.getData())), null);
        artifactRepository.delete(artifact); // the copy on the new job replaces it
        return rerun;
    }

    /**
     * Moves an ingested statement to another account: ingests its stored file into the target and, only when that succeeds, deletes it
     * (and the transactions it created) from this one. 409 when the file is no longer stored.
     */
    public FileIngestionResult moveStatement(UUID userId, UUID accountId, UUID statementId, UUID targetAccountId) {
        Account source = accountRepository.findByIdAndUser_Id(accountId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Account", accountId));
        Account target = accountRepository.findByIdAndUser_Id(targetAccountId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Account", targetAccountId));
        Statement statement = statementRepository.findByIdAndAccount_Id(statementId, source.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Statement", statementId));
        if (source.getId().equals(target.getId())) {
            throw new ValidationException("Pick a different account.");
        }
        StatementAccountMatcher.requireStatementAccount(target);
        JobArtifact stored = statement.getFileSha256() == null ? null
                : artifactRepository.findByUserAndSha(userId, statement.getFileSha256()).stream().findFirst().orElse(null);
        if (stored == null) {
            throw new ApiStatusException(HttpStatus.CONFLICT, "FILE_EXPIRED", HeldUploadService.FILE_GONE_MESSAGE);
        }
        UploadedFile file = new UploadedFile(stored.getFilename(), stored.getContentType(), stored.getData());
        // Target first: if it fails the source statement is untouched. No wrapping transaction (ingest and delete each
        // commit on their own), so the worst case is a delete failure leaving the statement on both accounts, never on neither.
        FileIngestionResult result = ingestionService.ingest(target.getId(), List.of(file), null);
        FileIngestionResult.FileSummary summary = result.fileDetails() == null || result.fileDetails().isEmpty()
                ? null : result.fileDetails().get(0);
        if (summary != null && "FAILED".equals(summary.status())) {
            FileIngestionResult.ErrorCode code = summary.errorCode();
            if (code == FileIngestionResult.ErrorCode.PASSWORD_REQUIRED || code == FileIngestionResult.ErrorCode.WRONG_PASSWORD) {
                throw new ApiStatusException(HttpStatus.UNPROCESSABLE_ENTITY, code.name(), summary.errorMessage());
            }
        }
        if (summary != null && "SUCCESS".equals(summary.status())) {
            deletionService.delete(source.getId(), statementId);
        }
        return result;
    }

    private UUID accountIdOf(Job job) {
        if (job.getPayload() == null || job.getPayload().isBlank()) {
            return null;
        }
        try {
            String id = objectMapper.readTree(job.getPayload()).path("accountId").asText(null);
            return id == null || id.isBlank() || "null".equals(id) ? null : UUID.fromString(id);
        } catch (Exception e) {
            return null;
        }
    }
}
