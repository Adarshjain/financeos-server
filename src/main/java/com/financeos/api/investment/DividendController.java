package com.financeos.api.investment;

import com.financeos.api.investment.dto.*;
import com.financeos.domain.investment.dividend.DividendService;
import com.financeos.domain.investment.dividend.DividendType;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.web.SortDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/investments/dividends")
public class DividendController {

    private final DividendService dividendService;

    public DividendController(DividendService dividendService) {
        this.dividendService = dividendService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public DividendResponse createDividend(@Valid @RequestBody CreateDividendRequest request) {
        return dividendService.createDividend(request);
    }

    @PutMapping("/{id}")
    public DividendResponse updateDividend(@PathVariable UUID id, @Valid @RequestBody UpdateDividendRequest request) {
        return dividendService.updateDividend(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteDividend(@PathVariable UUID id) {
        dividendService.deleteDividend(id);
    }

    @GetMapping
    public Page<DividendResponse> getDividends(
            @RequestParam(required = false) UUID holdingId,
            @RequestParam(required = false) UUID brokerAccountId,
            @RequestParam(required = false) UUID instrumentId,
            @RequestParam(required = false) DividendType type,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @PageableDefault(size = 25)
            @SortDefault.SortDefaults({
                    @SortDefault(sort = "payDate", direction = Sort.Direction.DESC),
                    @SortDefault(sort = "createdAt", direction = Sort.Direction.DESC)
            }) Pageable pageable) {
        return dividendService.getDividends(holdingId, brokerAccountId, instrumentId, type, from, to, pageable);
    }

    @GetMapping("/summary")
    public DividendSummaryResponse getSummary(
            @RequestParam(required = false) UUID holdingId,
            @RequestParam(required = false) UUID brokerAccountId,
            @RequestParam(required = false) UUID instrumentId,
            @RequestParam(required = false) DividendType type) {
        return dividendService.getSummary(holdingId, brokerAccountId, instrumentId, type);
    }

    @GetMapping("/suggestions")
    public DividendSuggestionsResponse scanSuggestions(
            @RequestParam(required = false) UUID brokerAccountId) {
        return dividendService.scanSuggestions(brokerAccountId);
    }

    @PostMapping("/suggestions/accept")
    public AcceptSuggestionsResponse acceptSuggestions(
            @Valid @RequestBody AcceptSuggestionsRequest request) {
        return dividendService.acceptSuggestions(request);
    }
}
