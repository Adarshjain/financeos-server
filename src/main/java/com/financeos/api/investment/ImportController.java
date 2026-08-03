package com.financeos.api.investment;

import com.financeos.api.investment.dto.*;
import com.financeos.domain.investment.imports.ImportService;
import com.financeos.domain.investment.imports.ImportSource;
import com.financeos.domain.investment.reconcile.Broker;
import com.financeos.domain.investment.reconcile.BrokerReconciliationService;
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

    public ImportController(ImportService importService, BrokerReconciliationService reconciliationService) {
        this.importService = importService;
        this.reconciliationService = reconciliationService;
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
    public ImportCommitResponse commit(@Valid @RequestBody ImportCommitRequest request) {
        return importService.commit(request.source(), request.brokerAccountId(), request.rows());
    }

    @PostMapping(value = "/reconcile/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ReconcilePreviewResponse reconcilePreview(
            @RequestParam Broker broker,
            @RequestParam UUID brokerAccountId,
            @RequestPart("tradebookFiles") List<MultipartFile> tradebookFiles,
            @RequestPart("taxpnlFiles") List<MultipartFile> taxpnlFiles,
            @RequestPart(value = "holdingsFile", required = false) MultipartFile holdingsFile) throws Exception {
        List<InputStream> tbStreams = new ArrayList<>();
        for (MultipartFile f : tradebookFiles) {
            tbStreams.add(f.getInputStream());
        }
        List<InputStream> taxStreams = new ArrayList<>();
        for (MultipartFile f : taxpnlFiles) {
            taxStreams.add(f.getInputStream());
        }
        InputStream holdingsStream = holdingsFile != null ? holdingsFile.getInputStream() : null;
        String holdingsFilename = holdingsFile != null ? holdingsFile.getOriginalFilename() : null;

        return reconciliationService.preview(broker, brokerAccountId, tbStreams, taxStreams, holdingsStream, holdingsFilename);
    }

    @PostMapping("/reconcile/commit")
    public ImportCommitResponse reconcileCommit(@Valid @RequestBody ReconcileCommitRequest request) {
        return reconciliationService.commit(request);
    }
}
