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
import com.financeos.domain.statement.StatementMoveService;
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
    private final StatementMoveService moveService;
    private final StatementIdentifier identifier;
    private final StatementPasswordVault passwordVault;
    private final EncryptionService encryptionService;
    private final ObjectMapper objectMapper;

    public StatementIntakeService(JobRepository jobRepository,
                                  JobService jobService,
                                  JobArtifactRepository artifactRepository,
                                  AccountRepository accountRepository,
                                  StatementRepository statementRepository,
                                  StatementMoveService moveService,
                                  StatementIdentifier identifier,
                                  StatementPasswordVault passwordVault,
                                  EncryptionService encryptionService,
                                  ObjectMapper objectMapper) {
        this.jobRepository = jobRepository;
        this.jobService = jobService;
        this.artifactRepository = artifactRepository;
        this.accountRepository = accountRepository;
        this.statementRepository = statementRepository;
        this.moveService = moveService;
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
        if (!job.getStatus().isTerminal()) {
            throw new ApiStatusException(HttpStatus.CONFLICT, "JOB_NOT_FINISHED", "This import is still running. Try again when it has finished.");
        }
        UUID accountId = accountIdOf(job);
        if (accountId == null) {
            throw new ValidationException("This import has no account yet. Pick an account for the held file instead.");
        }
        Account account = accountRepository.findByIdAndUser_Id(accountId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Account", accountId));
        List<JobArtifact> artifacts = jobService.getArtifacts(jobId);
        JobArtifact artifact = findArtifact(artifacts, fileIndex);
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
     * Moves an ingested statement, and the transactions its lines point at, to another account of the same type in place
     * (see {@link StatementMoveService}); no stored file is needed. 409 STATEMENT_EXISTS when the target already has the
     * statement (same file or same period). The result reports the moved rows as one successful file.
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
        if (source.getType() != target.getType()) {
            throw new ValidationException("Pick an account of the same type.");
        }
        boolean sameFile = statement.getFileSha256() != null
                && statementRepository.existsByAccountIdAndFileSha256(target.getId(), statement.getFileSha256());
        boolean samePeriod = statement.getPeriodStart() != null && statement.getPeriodEnd() != null
                && statementRepository.existsByAccountIdAndPeriodStartAndPeriodEnd(target.getId(), statement.getPeriodStart(), statement.getPeriodEnd());
        if (sameFile || samePeriod) {
            throw new ApiStatusException(HttpStatus.CONFLICT, "STATEMENT_EXISTS", "That account already has this statement.");
        }
        StatementMoveService.Moved moved = moveService.move(userId, source.getId(), target.getId(), statement.getId());
        String name = statement.getSource() == com.financeos.domain.statement.StatementSource.file_upload && statement.getSourceRef() != null
                ? statement.getSourceRef() : "Statement";
        return new FileIngestionResult(1, moved.moved(), moved.duplicatesFlagged(), List.of(
                new FileIngestionResult.FileSummary(name, "SUCCESS", moved.moved(), null, null, moved.moved(), moved.duplicatesFlagged())));
    }

    /** By the stored file index (positions shift once a file is used up); by position only for old rows that carry no index. */
    private static JobArtifact findArtifact(List<JobArtifact> artifacts, int fileIndex) {
        if (artifacts.stream().allMatch(a -> a.getFileIndex() == null)) {
            return fileIndex < 0 || fileIndex >= artifacts.size() ? null : artifacts.get(fileIndex);
        }
        return artifacts.stream().filter(a -> a.getFileIndex() != null && a.getFileIndex() == fileIndex).findFirst().orElse(null);
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
