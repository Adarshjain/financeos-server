package com.financeos.api.investment;

import com.financeos.api.investment.dto.TaxHarvestResponse;
import com.financeos.domain.investment.TaxHarvestService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Capital gains booked in a financial year and the open lots to harvest (the tax_harvest widget). */
@RestController
public class TaxHarvestController {

    private final TaxHarvestService taxHarvestService;

    public TaxHarvestController(TaxHarvestService taxHarvestService) {
        this.taxHarvestService = taxHarvestService;
    }

    /**
     * {@code fy} is the financial year's start year (default: the current Indian FY); {@code page}
     * (0-based) and {@code size} (default 20, at most 100) page the open lots, largest gain first.
     */
    @GetMapping("/api/v1/investments/tax/harvest")
    public ResponseEntity<TaxHarvestResponse> harvest(
            @RequestParam(required = false) Integer fy,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return ResponseEntity.ok(taxHarvestService.harvest(fy, page, size));
    }
}
