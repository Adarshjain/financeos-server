package com.financeos.api.investment;

import com.financeos.api.investment.dto.CreateFnoTradeRequest;
import com.financeos.api.investment.dto.FnoTradeListResponse;
import com.financeos.api.investment.dto.FnoTradeResponse;
import com.financeos.api.investment.dto.FnoSummaryResponse;
import com.financeos.domain.investment.fno.FnoReportService;
import com.financeos.domain.investment.fno.FnoTradeService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/investments/fno")
public class FnoTradeController {

    private final FnoTradeService fnoTradeService;
    private final FnoReportService fnoReportService;

    public FnoTradeController(FnoTradeService fnoTradeService, FnoReportService fnoReportService) {
        this.fnoTradeService = fnoTradeService;
        this.fnoReportService = fnoReportService;
    }

    @GetMapping
    public FnoTradeListResponse getFnoTrades() {
        return fnoTradeService.listTrades();
    }

    /** Paged, filtered trades: {@code content, totalElements, totalPages, number, size}. {@code GET /fno} keeps the legacy shape. */
    @GetMapping("/page")
    public Page<FnoTradeResponse> getFnoTradesPage(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) Integer fy,
            @RequestParam(required = false) FnoReportService.ContractFilter optionType,
            @RequestParam(required = false) UUID brokerAccountId,
            @RequestParam(required = false) String underlying,
            @RequestParam(required = false) String sort) {
        return fnoReportService.page(page, size, fy, optionType, brokerAccountId, underlying, sort);
    }

    @GetMapping("/summary")
    public FnoSummaryResponse getFnoSummary(
            @RequestParam(required = false) Integer fy,
            @RequestParam(required = false) UUID brokerAccountId) {
        return fnoReportService.summary(fy, brokerAccountId);
    }

    /** The financial year's trades as an ITR-ready CSV attachment. */
    @GetMapping("/export.csv")
    public ResponseEntity<byte[]> exportFnoCsv(@RequestParam(required = false) Integer fy) {
        byte[] body = fnoReportService.exportCsv(fy);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"fno-" + FnoReportService.exportLabel(fy) + ".csv\"")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(body);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public FnoTradeResponse createFnoTrade(@Valid @RequestBody CreateFnoTradeRequest request) {
        return fnoTradeService.createTrade(request);
    }

    @PutMapping("/{id}")
    public FnoTradeResponse updateFnoTrade(@PathVariable UUID id, @Valid @RequestBody CreateFnoTradeRequest request) {
        return fnoTradeService.updateTrade(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteFnoTrade(@PathVariable UUID id) {
        fnoTradeService.deleteTrade(id);
    }
}
