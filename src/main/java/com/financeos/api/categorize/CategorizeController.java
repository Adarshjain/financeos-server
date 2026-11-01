package com.financeos.api.categorize;

import com.financeos.api.categorize.dto.CategorizeRequest;
import com.financeos.api.categorize.dto.CategorizeResponse;
import com.financeos.core.security.UserContext;
import com.financeos.domain.categorization.CategoryAssigner;
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

    private final CategoryAssigner categoryAssigner;

    public CategorizeController(CategoryAssigner categoryAssigner) {
        this.categoryAssigner = categoryAssigner;
    }

    /** Suggest-as-you-type: the user's rules first, then the built-in engine; all null when neither has an answer. */
    @PostMapping
    public ResponseEntity<CategorizeResponse> categorize(@Valid @RequestBody CategorizeRequest request) {
        UUID currentSessionUserId = UserContext.getCurrentUserId();

        return ResponseEntity.ok(categoryAssigner.suggest(currentSessionUserId, request.description(), request.type())
                .map(s -> CategorizeResponse.of(s.categoryKey(), s.subcategoryKey(), s.source().name()))
                .orElseGet(CategorizeResponse::empty));
    }
}
