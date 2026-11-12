package com.financeos.api.transaction;

import com.financeos.api.transaction.dto.DuplicateReviewItem;
import com.financeos.core.exception.ValidationException;
import com.financeos.domain.transaction.DuplicateReviewService;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** The review queue's "Duplicate" rows with their likely twins, so the user sees the evidence before approving. */
@RestController
@RequestMapping("/api/v1/transactions/review")
public class DuplicateReviewController {

    private static final int MAX_IDS = 500;
    /** Keeps the twin lookup's IN lists under Oracle's 1000-element limit. */
    private static final int MAX_PAGE_SIZE = 200;

    private final DuplicateReviewService duplicateReviewService;

    public DuplicateReviewController(DuplicateReviewService duplicateReviewService) {
        this.duplicateReviewService = duplicateReviewService;
    }

    @GetMapping("/duplicates")
    public ResponseEntity<Page<DuplicateReviewItem>> duplicates(
            @ParameterObject @PageableDefault(size = 50, sort = { "date", "id" }, direction = Sort.Direction.DESC) Pageable pageable,
            @RequestParam(required = false) List<UUID> ids) {
        if (ids != null && ids.size() > MAX_IDS) {
            throw new ValidationException("ids can hold at most " + MAX_IDS + " transactions");
        }
        return ResponseEntity.ok(duplicateReviewService.duplicates(ids == null || ids.isEmpty() ? null : ids,
                PageRequest.of(pageable.getPageNumber(), Math.min(Math.max(pageable.getPageSize(), 1), MAX_PAGE_SIZE), pageable.getSort())));
    }
}
