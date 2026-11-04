package com.financeos.api.ingestion;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.api.ingestion.dto.IngestDtos.AssignHeldUploadRequest;
import com.financeos.api.ingestion.dto.IngestDtos.HeldUploadResponse;
import com.financeos.api.ingestion.dto.IngestDtos.MoveStatementRequest;
import com.financeos.api.ingestion.dto.IngestDtos.PasswordRetryRequest;
import com.financeos.api.job.dto.EnqueueResponse;
import com.financeos.api.job.dto.JobResponse;
import com.financeos.core.crypto.EncryptionService;
import com.financeos.core.exception.ValidationException;
import com.financeos.core.security.UserContext;
import com.financeos.domain.ingestion.FileIngestionResult;
import com.financeos.domain.ingestion.StatementIntakeService;
import com.financeos.domain.ingestion.held.HeldUploadService;
import com.financeos.domain.job.Job;
import com.financeos.domain.job.JobService;
import com.financeos.domain.job.JobTrigger;
import com.financeos.domain.job.JobType;
import com.financeos.domain.job.StagedFile;
import com.financeos.domain.job.handlers.StatementIngestPayload;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Statement intake without picking an account first (auto-detect and held files), and fixing an import afterwards
 * (password retry, move to another account). The per-account upload stays in {@link FileUploadController}.
 */
@RestController
@RequestMapping("/api/v1")
public class StatementIntakeController {

    private final JobService jobService;
    private final EncryptionService encryptionService;
    private final StatementIntakeService intakeService;
    private final HeldUploadService heldUploadService;
    private final ObjectMapper objectMapper;

    public StatementIntakeController(JobService jobService,
                                     EncryptionService encryptionService,
                                     StatementIntakeService intakeService,
                                     HeldUploadService heldUploadService,
                                     ObjectMapper objectMapper) {
        this.jobService = jobService;
        this.encryptionService = encryptionService;
        this.intakeService = intakeService;
        this.heldUploadService = heldUploadService;
        this.objectMapper = objectMapper;
    }

    /** Upload statements without an account: each file goes to the account it belongs to, or is held. */
    @PostMapping(value = "/ingest", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<EnqueueResponse> ingestAuto(@RequestPart("files") MultipartFile[] files,
                                                      @RequestParam(value = "password", required = false) String password) {
        UUID userId = UserContext.getCurrentUserId();
        List<StagedFile> staged = new ArrayList<>();
        if (files != null) {
            for (MultipartFile f : files) {
                try {
                    staged.add(new StagedFile(
                            f.getOriginalFilename() != null ? f.getOriginalFilename() : "unknown",
                            f.getContentType() != null ? f.getContentType() : "application/octet-stream",
                            f.getBytes()));
                } catch (IOException e) {
                    throw new ValidationException("Failed to read uploaded file: " + f.getOriginalFilename());
                }
            }
        }
        if (staged.isEmpty()) {
            throw new ValidationException("Choose at least one file.");
        }
        String encrypted = password == null || password.isBlank() ? null : encryptionService.encrypt(password);
        Job job = jobService.enqueue(userId, JobType.STATEMENT_INGEST, JobTrigger.USER,
                new StatementIngestPayload(null, encrypted), staged, null);
        return ResponseEntity.accepted().body(new EnqueueResponse(job.getId()));
    }

    /** Re-runs one stored file of a statement import with a password. 409 when the file is gone, 422 WRONG_PASSWORD when it still does not open. */
    @PostMapping("/jobs/{jobId}/files/{fileIndex}/password")
    public ResponseEntity<JobResponse> retryWithPassword(@PathVariable UUID jobId, @PathVariable int fileIndex,
                                                         @Valid @RequestBody PasswordRetryRequest request) {
        UUID userId = UserContext.getCurrentUserId();
        Job job = intakeService.rerunWithPassword(userId, jobId, fileIndex, request.password(), request.saveToAccount());
        return ResponseEntity.ok(JobResponse.from(job, objectMapper));
    }

    @GetMapping("/held-uploads")
    public ResponseEntity<List<HeldUploadResponse>> listHeld() {
        return ResponseEntity.ok(heldUploadService.list(UserContext.getCurrentUserId()).stream()
                .map(HeldUploadResponse::from).toList());
    }

    @PostMapping("/held-uploads/{id}/assign")
    public ResponseEntity<FileIngestionResult> assignHeld(@PathVariable UUID id, @Valid @RequestBody AssignHeldUploadRequest request) {
        return ResponseEntity.ok(heldUploadService.assign(UserContext.getCurrentUserId(), id, request.accountId(), request.password()));
    }

    @DeleteMapping("/held-uploads/{id}")
    public ResponseEntity<Void> discardHeld(@PathVariable UUID id) {
        heldUploadService.discard(UserContext.getCurrentUserId(), id);
        return ResponseEntity.noContent().build();
    }

    /** Moves an ingested statement to another account (409 when its file is no longer stored). */
    @PostMapping("/accounts/{accountId}/statements/{statementId}/move")
    public ResponseEntity<FileIngestionResult> moveStatement(@PathVariable UUID accountId, @PathVariable UUID statementId,
                                                             @Valid @RequestBody MoveStatementRequest request) {
        return ResponseEntity.ok(intakeService.moveStatement(UserContext.getCurrentUserId(), accountId, statementId,
                request.targetAccountId()));
    }
}
