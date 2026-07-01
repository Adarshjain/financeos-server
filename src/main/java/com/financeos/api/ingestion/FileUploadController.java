package com.financeos.api.ingestion;

import com.financeos.domain.ingestion.FileIngestionResult;
import com.financeos.domain.ingestion.FileIngestionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/accounts")
public class FileUploadController {

    private final FileIngestionService fileIngestionService;

    public FileUploadController(FileIngestionService fileIngestionService) {
        this.fileIngestionService = fileIngestionService;
    }

    @PostMapping("/{accountId}/ingest")
    public ResponseEntity<FileIngestionResult> ingestFiles(
            @PathVariable UUID accountId,
            @RequestParam("files") MultipartFile[] files) {
        List<MultipartFile> fileList = Arrays.asList(files);
        FileIngestionResult result = fileIngestionService.ingest(accountId, fileList);
        return ResponseEntity.ok(result);
    }
}
