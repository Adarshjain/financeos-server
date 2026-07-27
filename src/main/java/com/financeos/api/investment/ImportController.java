package com.financeos.api.investment;

import com.financeos.api.investment.dto.ImportCommitRequest;
import com.financeos.api.investment.dto.ImportCommitResponse;
import com.financeos.api.investment.dto.ImportPreviewResponse;
import com.financeos.domain.investment.imports.ImportService;
import com.financeos.domain.investment.imports.ImportSource;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/investments/imports")
public class ImportController {

    private final ImportService importService;

    public ImportController(ImportService importService) {
        this.importService = importService;
    }

    @PostMapping(value = "/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ImportPreviewResponse preview(
            @RequestPart("file") MultipartFile file,
            @RequestParam ImportSource source,
            @RequestParam UUID brokerAccountId) throws Exception {
        return importService.preview(file.getInputStream(), source, brokerAccountId);
    }

    @PostMapping("/commit")
    public ImportCommitResponse commit(@Valid @RequestBody ImportCommitRequest request) {
        return importService.commit(request.source(), request.brokerAccountId(), request.rows());
    }
}
