package com.financeos.api.categorize;

import com.financeos.api.categorize.dto.CategorizeRequest;
import com.financeos.api.categorize.dto.CategorizeResponse;
import com.financeos.core.security.UserContext;
import com.financeos.domain.categorization.CategorizationService;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/categorize")
@Slf4j
public class CategorizeController {

    private final CategorizationService categorizationService;

    public CategorizeController(CategorizationService categorizationService) {
        this.categorizationService = categorizationService;
    }

    /** Suggest-as-you-type: what the user's own rules say about this description; empty when none match. */
    @PostMapping
    public ResponseEntity<CategorizeResponse> categorize(@Valid @RequestBody CategorizeRequest request) {
        UUID currentSessionUserId = UserContext.getCurrentUserId();

        return ResponseEntity.ok(categorizationService.suggestFromRules(currentSessionUserId, request.description())
                .map(s -> CategorizeResponse.rule(s.categoryKey(), s.subcategoryKey()))
                .orElseGet(CategorizeResponse::empty));
    }
}
