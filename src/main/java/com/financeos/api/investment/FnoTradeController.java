package com.financeos.api.investment;

import com.financeos.api.investment.dto.CreateFnoTradeRequest;
import com.financeos.api.investment.dto.FnoTradeListResponse;
import com.financeos.api.investment.dto.FnoTradeResponse;
import com.financeos.domain.investment.fno.FnoTradeService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/investments/fno")
public class FnoTradeController {

    private final FnoTradeService fnoTradeService;

    public FnoTradeController(FnoTradeService fnoTradeService) {
        this.fnoTradeService = fnoTradeService;
    }

    @GetMapping
    public FnoTradeListResponse getFnoTrades() {
        return fnoTradeService.listTrades();
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
