package com.financeos.api.ingestion;

import com.financeos.api.job.dto.EnqueueResponse;
import com.financeos.core.exception.ResourceNotFoundException;
import com.financeos.core.exception.ValidationException;
import com.financeos.core.security.UserContext;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.account.AccountType;
import com.financeos.domain.job.Job;
import com.financeos.domain.job.JobService;
import com.financeos.domain.job.JobTrigger;
import com.financeos.domain.job.JobType;
import com.financeos.domain.job.StagedFile;
import com.financeos.domain.job.handlers.StatementIngestPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/accounts")
public class FileUploadController {

    private static final Logger log = LoggerFactory.getLogger(FileUploadController.class);

    static final Set<AccountType> STATEMENT_ACCOUNT_TYPES =
            EnumSet.of(AccountType.bank_account, AccountType.credit_card);
    static final String STATEMENT_TYPE_MESSAGE =
            "Statements can only be uploaded to bank or credit card accounts.";

    private final AccountRepository accountRepository;
    private final JobService jobService;

    public FileUploadController(AccountRepository accountRepository, JobService jobService) {
        this.accountRepository = accountRepository;
        this.jobService = jobService;
    }

    @PostMapping(value = "/{accountId}/ingest", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<EnqueueResponse> ingestFiles(
            @PathVariable UUID accountId,
            @RequestPart("files") MultipartFile[] files) {

        UUID currentUserId = UserContext.getCurrentUserId();
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new ResourceNotFoundException("Account", accountId));

        if (!account.getUser().getId().equals(currentUserId)) {
            log.error("Security Breach Attempt: User {} tried to ingest files to Account {} owned by User {}",
                    currentUserId, account.getId(), account.getUser().getId());
            throw new ValidationException("You do not have permission to ingest files to this account.");
        }

        // Statements only exist for bank and credit card accounts (mirrors the upload form's
        // account filter). Brokers are fed by broker imports; Wallet/Cash accounts are manual.
        if (!STATEMENT_ACCOUNT_TYPES.contains(account.getType())) {
            throw new ValidationException(STATEMENT_TYPE_MESSAGE);
        }

        List<StagedFile> stagedFiles = new ArrayList<>();
        if (files != null) {
            for (MultipartFile f : files) {
                try {
                    stagedFiles.add(new StagedFile(
                            f.getOriginalFilename() != null ? f.getOriginalFilename() : "unknown",
                            f.getContentType() != null ? f.getContentType() : "application/octet-stream",
                            f.getBytes()
                    ));
                } catch (IOException e) {
                    throw new ValidationException("Failed to read uploaded file: " + f.getOriginalFilename());
                }
            }
        }

        Job job = jobService.enqueue(
                currentUserId,
                JobType.STATEMENT_INGEST,
                JobTrigger.USER,
                new StatementIngestPayload(accountId),
                stagedFiles,
                null
        );

        return ResponseEntity.accepted().body(new EnqueueResponse(job.getId()));
    }
}
