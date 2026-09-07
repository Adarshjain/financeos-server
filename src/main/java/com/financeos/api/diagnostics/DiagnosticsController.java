package com.financeos.api.diagnostics;

import com.financeos.core.diagnostics.DiagnosticsService;
import com.financeos.core.diagnostics.dto.DiagnosticsLookupResponse;
import com.financeos.core.diagnostics.dto.RawLogEntry;
import com.financeos.core.security.AdminGuard;
import com.financeos.core.security.UserContext;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/diagnostics/lookup")
public class DiagnosticsController {

    private final DiagnosticsService diagnosticsService;
    private final AdminGuard adminGuard;

    public DiagnosticsController(DiagnosticsService diagnosticsService, AdminGuard adminGuard) {
        this.diagnosticsService = diagnosticsService;
        this.adminGuard = adminGuard;
    }

    @GetMapping
    public ResponseEntity<DiagnosticsLookupResponse> lookup(
            @RequestParam String ref,
            @RequestParam(required = false, defaultValue = "auto") String type,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant since,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant until) {

        adminGuard.require();
        UUID adminUserId = UserContext.getCurrentUserId();

        DiagnosticsLookupResponse response = diagnosticsService.lookup(adminUserId, ref, type, since, until);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/raw")
    public ResponseEntity<List<RawLogEntry>> raw(
            @RequestParam String ref,
            @RequestParam(required = false, defaultValue = "auto") String type,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant since,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant until) {

        adminGuard.require();
        UUID adminUserId = UserContext.getCurrentUserId();

        List<RawLogEntry> lines = diagnosticsService.raw(adminUserId, ref, type, since, until);
        return ResponseEntity.ok(lines);
    }
}
