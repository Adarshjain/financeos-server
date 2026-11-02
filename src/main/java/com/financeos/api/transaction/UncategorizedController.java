package com.financeos.api.transaction;

import com.financeos.api.transaction.dto.ApplyUncategorizedRequest;
import com.financeos.api.transaction.dto.ApplyUncategorizedResponse;
import com.financeos.api.transaction.dto.CategoryCountsResponse;
import com.financeos.api.transaction.dto.CategoryPick;
import com.financeos.api.transaction.dto.UncategorizedExistsResponse;
import com.financeos.api.transaction.dto.UncategorizedGroupResponse;
import com.financeos.api.transaction.dto.UncategorizedSummaryResponse;
import com.financeos.api.transaction.dto.UncategorizedTransactionResponse;
import com.financeos.api.transaction.dto.UndoUncategorizedRequest;
import com.financeos.api.transaction.dto.UndoUncategorizedResponse;
import com.financeos.core.exception.ValidationException;
import com.financeos.core.security.UserContext;
import com.financeos.domain.categorization.UncategorizedService;
import com.financeos.domain.transaction.TransactionType;
import jakarta.validation.Valid;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;

/** Category helpers of the transaction list: recent picks, filter counts and "Sort them" (uncategorised, grouped). */
@RestController
@RequestMapping("/api/v1/transactions")
public class UncategorizedController {

    private static final int MAX_PAGE_SIZE = 200;

    private final UncategorizedService service;

    public UncategorizedController(UncategorizedService service) {
        this.service = service;
    }

    /** Up to four distinct category/sub-category pairs the user picked for this direction, newest first. */
    @GetMapping("/category-recent")
    public ResponseEntity<List<CategoryPick>> categoryRecent(@RequestParam String direction) {
        TransactionType type = switch (direction.trim().toUpperCase(Locale.ROOT)) {
            case "OUT" -> TransactionType.DEBIT;
            case "IN" -> TransactionType.CREDIT;
            default -> throw new ValidationException("direction must be OUT or IN");
        };
        return ResponseEntity.ok(service.recentPicks(UserContext.getCurrentUserId(), type));
    }

    /** Transactions per category and sub-category over the whole ledger, for the filter sheet. */
    @GetMapping("/category-counts")
    public ResponseEntity<CategoryCountsResponse> categoryCounts() {
        return ResponseEntity.ok(service.categoryCounts(UserContext.getCurrentUserId()));
    }

    /** Whether Sort them has anything to show: one cheap probe, what the page load calls. */
    @GetMapping("/uncategorized/exists")
    public ResponseEntity<UncategorizedExistsResponse> exists() {
        return ResponseEntity.ok(new UncategorizedExistsResponse(service.exists(UserContext.getCurrentUserId())));
    }

    @GetMapping("/uncategorized/summary")
    public ResponseEntity<UncategorizedSummaryResponse> summary() {
        return ResponseEntity.ok(service.summary(UserContext.getCurrentUserId()));
    }

    /** Groups of two or more by count then total, then the one-offs; one page of that sequence. {@code q} filters by label. */
    @GetMapping("/uncategorized/groups")
    public ResponseEntity<Page<UncategorizedGroupResponse>> groups(
            @RequestParam(required = false) String q,
            @ParameterObject @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(service.groupPage(UserContext.getCurrentUserId(), q, unsorted(pageable)));
    }

    @GetMapping("/uncategorized/groups/{groupKey}/transactions")
    public ResponseEntity<Page<UncategorizedTransactionResponse>> groupTransactions(
            @PathVariable String groupKey,
            @ParameterObject @PageableDefault(size = 50) Pageable pageable) {
        return ResponseEntity.ok(service.groupTransactions(UserContext.getCurrentUserId(), groupKey, unsorted(pageable)));
    }

    @PostMapping("/uncategorized/apply")
    public ResponseEntity<ApplyUncategorizedResponse> apply(@Valid @RequestBody ApplyUncategorizedRequest request) {
        return ResponseEntity.ok(service.apply(UserContext.getCurrentUserId(), request));
    }

    /** Takes back one apply: the rows it categorised and the rule it created or re-pointed. */
    @PostMapping("/uncategorized/undo")
    public ResponseEntity<UndoUncategorizedResponse> undo(@Valid @RequestBody UndoUncategorizedRequest request) {
        return ResponseEntity.ok(service.undo(UserContext.getCurrentUserId(), request));
    }

    /** The order is fixed by the server; the size is capped. */
    private static Pageable unsorted(Pageable p) {
        return PageRequest.of(p.getPageNumber(), Math.min(Math.max(p.getPageSize(), 1), MAX_PAGE_SIZE));
    }
}
