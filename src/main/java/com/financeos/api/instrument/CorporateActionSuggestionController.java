package com.financeos.api.instrument;

import com.financeos.api.instrument.dto.CorporateActionReviewResponse;
import com.financeos.api.instrument.dto.CorporateActionSuggestionResponse;
import com.financeos.api.instrument.dto.CreateCorporateActionRequest;
import com.financeos.domain.instrument.corporateaction.detect.CorporateActionReviewService;
import com.financeos.domain.instrument.corporateaction.detect.CorporateActionSuggestionService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

/** Detected corporate actions: look at one, undo an automatic one, accept or dismiss a held one. */
@RestController
@RequestMapping("/api/v1/corporate-action-suggestions")
public class CorporateActionSuggestionController {

    private final CorporateActionSuggestionService service;
    private final CorporateActionReviewService reviewService;

    public CorporateActionSuggestionController(CorporateActionSuggestionService service,
                                               CorporateActionReviewService reviewService) {
        this.service = service;
        this.reviewService = reviewService;
    }

    /** Pending and automatic suggestions with their evidence, plus held stocks that no longer trade. */
    @GetMapping
    public CorporateActionReviewResponse getCorporateActionReview() {
        return reviewService.review();
    }

    @GetMapping("/{id}")
    public CorporateActionSuggestionResponse getCorporateActionSuggestion(@PathVariable UUID id) {
        return service.get(id);
    }

    @PostMapping("/{id}/undo")
    public CorporateActionSuggestionResponse undoCorporateActionSuggestion(@PathVariable UUID id) {
        return service.undo(id);
    }

    @PostMapping("/{id}/accept")
    public CorporateActionSuggestionResponse acceptCorporateActionSuggestion(@PathVariable UUID id,
                                                    @Valid @RequestBody CreateCorporateActionRequest request) {
        return service.accept(id, request);
    }

    @PostMapping("/{id}/dismiss")
    public CorporateActionSuggestionResponse dismissCorporateActionSuggestion(@PathVariable UUID id) {
        return service.dismiss(id);
    }
}
