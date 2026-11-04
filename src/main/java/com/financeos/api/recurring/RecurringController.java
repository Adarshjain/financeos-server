package com.financeos.api.recurring;

import com.financeos.api.recurring.dto.RecurringConvertedRequest;
import com.financeos.api.recurring.dto.RecurringSeriesDto;
import com.financeos.core.security.UserContext;
import com.financeos.domain.recurring.RecurringService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

/** Detected regular payments: proposals to track or dismiss, and the tracked list. */
@RestController
@RequestMapping("/api/v1/recurring")
public class RecurringController {

    private final RecurringService recurringService;

    public RecurringController(RecurringService recurringService) {
        this.recurringService = recurringService;
    }

    private static UUID requireCurrentUserId() {
        UUID userId = UserContext.getCurrentUserId();
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User is not authenticated");
        }
        return userId;
    }

    @GetMapping("/proposals")
    public List<RecurringSeriesDto> proposals() {
        return recurringService.proposals(requireCurrentUserId());
    }

    @GetMapping("/tracked")
    public List<RecurringSeriesDto> tracked() {
        return recurringService.tracked(requireCurrentUserId());
    }

    @PostMapping("/{id}/track")
    public RecurringSeriesDto track(@PathVariable UUID id) {
        return recurringService.track(requireCurrentUserId(), id);
    }

    /** "Not regular". */
    @PostMapping("/{id}/dismiss")
    public RecurringSeriesDto dismiss(@PathVariable UUID id) {
        return recurringService.dismiss(requireCurrentUserId(), id);
    }

    @PostMapping("/{id}/stop")
    public RecurringSeriesDto stop(@PathVariable UUID id) {
        return recurringService.stop(requireCurrentUserId(), id);
    }

    @PostMapping("/{id}/converted")
    public RecurringSeriesDto converted(@PathVariable UUID id, @Valid @RequestBody RecurringConvertedRequest body) {
        return recurringService.converted(requireCurrentUserId(), id, body.loanId());
    }
}
