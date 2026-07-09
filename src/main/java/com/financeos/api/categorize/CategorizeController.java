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

    @PostMapping
    public ResponseEntity<CategorizeResponse> categorize(@Valid @RequestBody CategorizeRequest request) {
        UUID currentSessionUserId = UserContext.getCurrentUserId();

        CategorizationService.SuggestionResult result =
                categorizationService.suggestForDescription(currentSessionUserId, request.description());

        return ResponseEntity.ok(CategorizeResponse.from(result));
    }
}
