package com.financeos.e2e;

import com.financeos.core.security.UserContext;
import com.financeos.domain.recurring.RecurringJob;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

/** E2E only: runs the recurring-payment detection for the current user now, instead of waiting for the daily job. */
@RestController
@Profile("e2e")
@RequestMapping("/api/e2e/recurring")
public class E2eRecurringController {

    private final RecurringJob recurringJob;

    public E2eRecurringController(RecurringJob recurringJob) {
        this.recurringJob = recurringJob;
    }

    /** Links and advances tracked series, then detects new ones, synchronously, for the calling user only. */
    @PostMapping("/detect")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void detect() {
        UUID userId = UserContext.getCurrentUserId();
        if (userId == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User is not authenticated");
        recurringJob.runUser(userId, true);
    }
}
