package com.financeos.api.epf;

import com.financeos.api.epf.dto.EpfImportPreviewResponse;
import com.financeos.api.epf.dto.EpfImportResultResponse;
import com.financeos.domain.epf.EpfImportService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/epf/import")
public class EpfImportController {

    private final EpfImportService importService;

    public EpfImportController(EpfImportService importService) {
        this.importService = importService;
    }

    @PostMapping(value = "/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public EpfImportPreviewResponse previewEpfImport(@RequestPart("file") MultipartFile file,
                                            @RequestParam(required = false) UUID employerId) throws IOException {
        return importService.preview(file.getBytes(), employerId);
    }

    @PostMapping(value = "/commit", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public EpfImportResultResponse commitEpfImport(@RequestPart("file") MultipartFile file,
                                          @RequestParam(required = false) UUID employerId) throws IOException {
        return importService.commit(file.getBytes(), employerId);
    }
}
