package com.financeos.api.nps;

import com.financeos.api.nps.dto.NpsImportCommitRequest;
import com.financeos.api.nps.dto.NpsImportPreviewResponse;
import com.financeos.api.nps.dto.NpsImportResultResponse;
import com.financeos.api.nps.dto.NpsStatementResponse;
import com.financeos.domain.nps.NpsImportService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

/** Importing a Protean NPS transaction statement: upload and preview, then commit. */
@RestController
@RequestMapping("/api/v1/nps/import")
public class NpsImportController {

    private final NpsImportService importService;

    public NpsImportController(NpsImportService importService) {
        this.importService = importService;
    }

    /** A locked file nobody's password opens answers 200 with {@code passwordNeeded}, not an error. */
    @PostMapping(value = "/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public NpsImportPreviewResponse previewNpsStatement(@RequestPart("file") MultipartFile file,
                                                        @RequestParam(required = false) String password,
                                                        @RequestParam(required = false) UUID accountId)
            throws IOException {
        return importService.preview(file.getBytes(), password, accountId);
    }

    @PostMapping("/commit")
    public NpsImportResultResponse commitNpsStatement(@RequestBody NpsImportCommitRequest request) {
        return importService.commit(request);
    }

    @GetMapping("/statements")
    public List<NpsStatementResponse> listNpsStatements(@RequestParam(required = false) UUID accountId) {
        return importService.statements(accountId);
    }

    @DeleteMapping("/statements/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteNpsStatement(@PathVariable UUID id) {
        importService.deleteStatement(id);
    }
}
