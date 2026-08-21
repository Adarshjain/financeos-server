package com.financeos.api.investment;

import com.financeos.api.investment.dto.*;
import com.financeos.domain.investment.imports.ImportService;
import com.financeos.domain.investment.imports.ImportSource;
import com.financeos.domain.investment.reconcile.Broker;
import com.financeos.domain.investment.reconcile.BrokerReconciliationService;
import com.financeos.domain.investment.reconcile.ImportAssetScope;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/investments/imports")
public class ImportController {

    private final ImportService importService;
    private final BrokerReconciliationService reconciliationService;
    private final com.financeos.domain.job.JobService jobService;

    public ImportController(ImportService importService,
                            BrokerReconciliationService reconciliationService,
                            com.financeos.domain.job.JobService jobService) {
        this.importService = importService;
        this.reconciliationService = reconciliationService;
        this.jobService = jobService;
    }

    @PostMapping(value = "/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ImportPreviewResponse preview(
            @RequestPart("file") MultipartFile file,
            @RequestParam ImportSource source,
            @RequestParam UUID brokerAccountId,
            @RequestParam(required = false) String password) throws Exception {
        return importService.preview(file.getInputStream(), source, brokerAccountId, password);
    }

    @PostMapping("/commit")
    public org.springframework.http.ResponseEntity<com.financeos.api.job.dto.EnqueueResponse> commit(@Valid @RequestBody ImportCommitRequest request) {
        UUID currentUserId = com.financeos.core.security.UserContext.getCurrentUserId();
        com.financeos.domain.job.Job job = jobService.enqueue(
                currentUserId,
                com.financeos.domain.job.JobType.INVESTMENT_IMPORT_COMMIT,
                com.financeos.domain.job.JobTrigger.USER,
                new com.financeos.domain.job.handlers.InvestmentImportCommitPayload(request),
                null,
                "import-commit"
        );
        return org.springframework.http.ResponseEntity.accepted().body(new com.financeos.api.job.dto.EnqueueResponse(job.getId()));
    }

    @PostMapping(value = "/reconcile/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ReconcilePreviewResponse reconcilePreview(
            @RequestParam Broker broker,
            @RequestParam UUID brokerAccountId,
            @RequestParam(required = false, defaultValue = "all") ImportAssetScope assetScope,
            @RequestPart(value = "tradebookFiles", required = false) List<MultipartFile> tradebookFiles,
            @RequestPart("taxpnlFiles") List<MultipartFile> taxpnlFiles,
            @RequestPart(value = "holdingsFile", required = false) MultipartFile holdingsFile) throws Exception {
        List<InputStream> tbStreams = new ArrayList<>();
        if (tradebookFiles != null) {
            for (MultipartFile f : tradebookFiles) {
                tbStreams.add(f.getInputStream());
            }
        }
        List<InputStream> taxStreams = new ArrayList<>();
        if (taxpnlFiles != null) {
            for (MultipartFile f : taxpnlFiles) {
                taxStreams.add(f.getInputStream());
            }
        }
        InputStream holdingsStream = holdingsFile != null ? holdingsFile.getInputStream() : null;
        String holdingsFilename = holdingsFile != null ? holdingsFile.getOriginalFilename() : null;

        return reconciliationService.preview(broker, brokerAccountId, tbStreams, taxStreams, holdingsStream, holdingsFilename, assetScope);
    }

    @PostMapping("/reconcile/commit")
    public org.springframework.http.ResponseEntity<com.financeos.api.job.dto.EnqueueResponse> reconcileCommit(@Valid @RequestBody ReconcileCommitRequest request) {
        UUID currentUserId = com.financeos.core.security.UserContext.getCurrentUserId();
        com.financeos.domain.job.Job job = jobService.enqueue(
                currentUserId,
                com.financeos.domain.job.JobType.BROKER_RECONCILE_COMMIT,
                com.financeos.domain.job.JobTrigger.USER,
                new com.financeos.domain.job.handlers.BrokerReconcileCommitPayload(request),
                null,
                "reconcile-commit"
        );
        return org.springframework.http.ResponseEntity.accepted().body(new com.financeos.api.job.dto.EnqueueResponse(job.getId()));
    }
}
