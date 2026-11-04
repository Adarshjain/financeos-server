package com.financeos.domain.ingestion.held;

import com.financeos.core.exception.ApiStatusException;
import com.financeos.core.exception.ResourceNotFoundException;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.ingestion.FileIngestionResult;
import com.financeos.domain.ingestion.FileIngestionService;
import com.financeos.domain.ingestion.StatementAccountMatcher;
import com.financeos.domain.ingestion.StatementIdentifier;
import com.financeos.domain.ingestion.StatementPasswordVault;
import com.financeos.domain.ingestion.UploadedFile;
import com.financeos.domain.job.JobArtifact;
import com.financeos.domain.job.JobArtifactRepository;
import com.financeos.domain.notification.feed.AppNotificationKind;
import com.financeos.domain.notification.feed.AppNotificationRefs;
import com.financeos.domain.notification.feed.NotificationFeedService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Files uploaded without an account that could not be matched to one: hold them (inbox item + one feed
 * notification each), then let the user assign each to an account or discard it.
 */
@Service
public class HeldUploadService {

    public static final String FILE_GONE_MESSAGE = "The file is no longer stored. Upload it again.";

    private final HeldUploadRepository heldRepository;
    private final JobArtifactRepository artifactRepository;
    private final AccountRepository accountRepository;
    private final FileIngestionService ingestionService;
    private final StatementPasswordVault passwordVault;
    private final StatementIdentifier identifier;
    private final NotificationFeedService feedService;

    public HeldUploadService(HeldUploadRepository heldRepository,
                             JobArtifactRepository artifactRepository,
                             AccountRepository accountRepository,
                             FileIngestionService ingestionService,
                             StatementPasswordVault passwordVault,
                             StatementIdentifier identifier,
                             NotificationFeedService feedService) {
        this.heldRepository = heldRepository;
        this.artifactRepository = artifactRepository;
        this.accountRepository = accountRepository;
        this.ingestionService = ingestionService;
        this.passwordVault = passwordVault;
        this.identifier = identifier;
        this.feedService = feedService;
    }

    /** Keeps the stored file past the job's success and records the held row, the inbox item's source, and one feed entry. */
    @Transactional
    public HeldUpload hold(UUID userId, JobArtifact artifact, int fileIndex, String bankId, String last4,
                           HeldUpload.Reason reason, List<UUID> candidates) {
        artifact.setRetained(true);
        artifactRepository.save(artifact);

        HeldUpload held = new HeldUpload();
        held.setUserId(userId);
        held.setJobId(artifact.getJobId());
        held.setArtifactId(artifact.getId());
        held.setFileIndex(fileIndex);
        held.setFileName(artifact.getFilename());
        held.setDetectedBankId(bankId);
        held.setDetectedLast4(last4);
        held.setReason(reason);
        held.setCandidateIds(candidates);
        held = heldRepository.save(held);

        String body = bankId == null || bankId.isBlank() ? artifact.getFilename() : artifact.getFilename() + " · " + bankId;
        feedService.record(userId, AppNotificationKind.UPLOAD_HELD, "A statement needs an account", body,
                AppNotificationRefs.ofJob(artifact.getJobId(), "STATEMENT_INGEST"), "held-upload:" + held.getId());
        return held;
    }

    @Transactional(readOnly = true)
    public List<HeldUpload> list(UUID userId) {
        return heldRepository.findByUserIdAndResolvedAtIsNullOrderByCreatedAtDesc(userId);
    }

    /** Ingests the stored file into the account; the password (if given) is saved to the account when it opened the file. */
    public FileIngestionResult assign(UUID userId, UUID heldId, UUID accountId, String password) {
        HeldUpload held = heldRepository.findByIdAndUserId(heldId, userId)
                .filter(h -> h.getResolvedAt() == null)
                .orElseThrow(() -> new ResourceNotFoundException("HeldUpload", heldId));
        Account account = accountRepository.findByIdAndUser_Id(accountId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Account", accountId));
        StatementAccountMatcher.requireStatementAccount(account);
        JobArtifact artifact = artifactRepository.findById(held.getArtifactId())
                .orElseThrow(() -> new ApiStatusException(HttpStatus.CONFLICT, "FILE_EXPIRED", FILE_GONE_MESSAGE));

        byte[] bytes = artifact.getData();
        boolean passwordGiven = password != null && !password.isBlank();
        boolean locked = passwordGiven && identifier.isLocked(bytes);
        FileIngestionResult result = ingestionService.ingest(accountId,
                List.of(new UploadedFile(artifact.getFilename(), artifact.getContentType(), bytes)), null, passwordGiven ? password : null);

        FileIngestionResult.FileSummary file = result.fileDetails().isEmpty() ? null : result.fileDetails().get(0);
        if (file != null && "FAILED".equals(file.status())) {
            FileIngestionResult.ErrorCode code = file.errorCode();
            if (code == FileIngestionResult.ErrorCode.PASSWORD_REQUIRED || code == FileIngestionResult.ErrorCode.WRONG_PASSWORD) {
                throw new ApiStatusException(HttpStatus.UNPROCESSABLE_ENTITY, code.name(), file.errorMessage());
            }
            return result; // not a password problem: the file stays held so another account can be tried
        }
        if (locked) {
            passwordVault.save(userId, accountId, password);
        }
        resolve(held, artifact);
        return result;
    }

    /** Throws the held file away (and its stored copy). */
    @Transactional
    public void discard(UUID userId, UUID heldId) {
        HeldUpload held = heldRepository.findByIdAndUserId(heldId, userId)
                .filter(h -> h.getResolvedAt() == null)
                .orElseThrow(() -> new ResourceNotFoundException("HeldUpload", heldId));
        resolve(held, artifactRepository.findById(held.getArtifactId()).orElse(null));
    }

    private void resolve(HeldUpload held, JobArtifact artifact) {
        held.setResolvedAt(Instant.now());
        heldRepository.save(held);
        if (artifact != null) {
            artifactRepository.delete(artifact);
        }
    }
}
