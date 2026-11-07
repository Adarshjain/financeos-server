package com.financeos.api.imports;

import com.financeos.api.imports.dto.ImportRecordResponse;
import com.financeos.core.security.UserContext;
import com.financeos.domain.imports.ImportHistoryService;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Every file or batch the user has imported, newest first, across statements, NPS, EPF and broker imports. */
@RestController
@RequestMapping("/api/v1/imports")
public class ImportHistoryController {

    private final ImportHistoryService service;

    public ImportHistoryController(ImportHistoryService service) {
        this.service = service;
    }

    @GetMapping
    public Page<ImportRecordResponse> list(@RequestParam(defaultValue = "0") int page,
                                           @RequestParam(defaultValue = "50") int size,
                                           @RequestParam(required = false) String kind) {
        UUID userId = UserContext.getCurrentUserId();
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User is not authenticated");
        }
        return service.list(userId, kind, page, size);
    }
}
