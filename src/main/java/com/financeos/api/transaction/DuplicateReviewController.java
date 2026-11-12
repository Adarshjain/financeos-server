package com.financeos.api.transaction;

import com.financeos.api.transaction.dto.DuplicateReviewItem;
import com.financeos.domain.transaction.DuplicateReviewService;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The review queue's "Duplicate" rows with their likely twins, so the user sees the evidence before approving. */
@RestController
@RequestMapping("/api/v1/transactions/review")
public class DuplicateReviewController {

    private final DuplicateReviewService duplicateReviewService;

    public DuplicateReviewController(DuplicateReviewService duplicateReviewService) {
        this.duplicateReviewService = duplicateReviewService;
    }

    @GetMapping("/duplicates")
    public ResponseEntity<Page<DuplicateReviewItem>> duplicates(
            @ParameterObject @PageableDefault(size = 50, sort = { "date", "id" }, direction = Sort.Direction.DESC) Pageable pageable) {
        return ResponseEntity.ok(duplicateReviewService.duplicates(pageable));
    }
}
