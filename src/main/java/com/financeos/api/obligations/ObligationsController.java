package com.financeos.api.obligations;

import com.financeos.api.obligations.dto.ObligationsResponse;
import com.financeos.core.security.UserContext;
import com.financeos.domain.obligations.ObligationsService;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

/**
 * Upcoming obligations: EMIs, lending returns, card bills and expected statements, overdue
 * first. {@code months} is the look-ahead window (1..12, default 3); {@code kinds} is an
 * optional CSV of {@code emi,lending_due,card_bill,statement_expected} (default all).
 */
@RestController
@RequestMapping("/api/v1/obligations")
public class ObligationsController {

    private final ObligationsService obligationsService;

    public ObligationsController(ObligationsService obligationsService) {
        this.obligationsService = obligationsService;
    }

    private UUID requireCurrentUserId() {
        UUID userId = UserContext.getCurrentUserId();
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User is not authenticated");
        }
        return userId;
    }

    @GetMapping("/upcoming")
    public ObligationsResponse getUpcomingObligations(@RequestParam(defaultValue = "3") int months,
                                                      @RequestParam(required = false) String kinds) {
        UUID userId = requireCurrentUserId();
        return obligationsService.upcoming(userId, months, ObligationsService.parseKinds(kinds));
    }
}
