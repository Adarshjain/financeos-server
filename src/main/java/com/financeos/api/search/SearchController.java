package com.financeos.api.search;

import com.financeos.api.search.dto.GlobalSearchResponse;
import com.financeos.domain.search.GlobalSearchService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Global search: a few matches per record type in one request. */
@RestController
public class SearchController {

    private final GlobalSearchService searchService;

    public SearchController(GlobalSearchService searchService) {
        this.searchService = searchService;
    }

    /** Up to 5 matches per group for the trimmed {@code q} (3-100 characters, else 400). */
    @GetMapping("/api/v1/search")
    public ResponseEntity<GlobalSearchResponse> globalSearch(@RequestParam(required = false) String q) {
        return ResponseEntity.ok(searchService.search(q));
    }
}
