package com.financeos.api.investment;

import com.financeos.api.investment.dto.CreateDividendRequest;
import com.financeos.api.investment.dto.DividendResponse;
import com.financeos.api.investment.dto.UpdateDividendRequest;
import com.financeos.domain.investment.dividend.DividendService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

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
            @PageableDefault(size = 50, sort = "payDate") Pageable pageable) {
        return dividendService.getDividends(holdingId, brokerAccountId, instrumentId, pageable);
    }
}
