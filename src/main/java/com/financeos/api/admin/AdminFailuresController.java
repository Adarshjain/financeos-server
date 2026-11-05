package com.financeos.api.admin;

import com.financeos.api.admin.dto.FailedGmailItemResponse;
import com.financeos.api.admin.dto.FailedJobResponse;
import com.financeos.api.admin.dto.FailureReasonRequest;
import com.financeos.api.admin.dto.FailureSummaryResponse;
import com.financeos.api.admin.dto.RetryAllRequest;
import com.financeos.api.admin.dto.RetryAllResponse;
import com.financeos.api.job.dto.EnqueueResponse;
import com.financeos.core.security.AdminGuard;
import com.financeos.domain.failure.FailureAdminService;
import com.financeos.gmail.domain.GmailProcessedStatus;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Admin-only Failures tool, across users. Every endpoint needs an email listed in ADMIN_EMAILS (fail-closed: a blank list
 * admits nobody, unlike the open-to-all diagnostics lookup). Deliberately not {@code @Transactional}: see {@link FailureAdminService}.
 */
@RestController
@RequestMapping("/api/v1/admin/failures")
public class AdminFailuresController {

    private final AdminGuard adminGuard;
    private final FailureAdminService service;

    public AdminFailuresController(AdminGuard adminGuard, FailureAdminService service) {
        this.adminGuard = adminGuard;
        this.service = service;
    }

    @GetMapping("/summary")
    public FailureSummaryResponse summary() {
        adminGuard.requireExplicit();
        return service.summary();
    }

    @GetMapping("/jobs")
    public Page<FailedJobResponse> listJobs(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "false") boolean includeDismissed) {
        adminGuard.requireExplicit();
        return service.listJobs(page, size, includeDismissed);
    }

    @GetMapping("/jobs/{id}")
    public FailedJobResponse jobDetail(@PathVariable UUID id) {
        adminGuard.requireExplicit();
        return service.jobDetail(id);
    }

    @GetMapping("/gmail")
    public Page<FailedGmailItemResponse> listGmail(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) GmailProcessedStatus status) {
        adminGuard.requireExplicit();
        return service.listGmail(page, size, status);
    }

    @GetMapping("/gmail/{ledgerId}")
    public FailedGmailItemResponse gmailDetail(@PathVariable UUID ledgerId) {
        adminGuard.requireExplicit();
        return service.gmailDetail(ledgerId);
    }

    @PostMapping("/gmail/{ledgerId}/retry")
    public ResponseEntity<EnqueueResponse> retryGmail(@PathVariable UUID ledgerId) {
        String admin = adminGuard.requireExplicit();
        return ResponseEntity.accepted().body(new EnqueueResponse(service.retryGmail(ledgerId, admin)));
    }

    @PostMapping("/gmail/{ledgerId}/skip")
    public ResponseEntity<Void> skipGmail(@PathVariable UUID ledgerId, @Valid @RequestBody FailureReasonRequest request) {
        String admin = adminGuard.requireExplicit();
        service.skipGmail(ledgerId, request.reason().trim(), admin);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/jobs/{id}/retry")
    public ResponseEntity<EnqueueResponse> retryJob(@PathVariable UUID id) {
        String admin = adminGuard.requireExplicit();
        return ResponseEntity.accepted().body(new EnqueueResponse(service.retryJob(id, admin).getId()));
    }

    @PostMapping("/jobs/{id}/dismiss")
    public ResponseEntity<Void> dismissJob(@PathVariable UUID id, @Valid @RequestBody FailureReasonRequest request) {
        String admin = adminGuard.requireExplicit();
        service.dismissJob(id, request.reason().trim(), admin);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/retry-all")
    public RetryAllResponse retryAll(@Valid @RequestBody RetryAllRequest request) {
        String admin = adminGuard.requireExplicit();
        return request.kind() == RetryAllRequest.Kind.gmail ? service.retryAllGmail(admin) : service.retryAllJobs(admin);
    }
}
