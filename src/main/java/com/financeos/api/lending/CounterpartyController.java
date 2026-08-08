package com.financeos.api.lending;

import com.financeos.api.lending.dto.CounterpartyResponse;
import com.financeos.api.lending.dto.CreateCounterpartyRequest;
import com.financeos.api.lending.dto.UpdateCounterpartyRequest;
import com.financeos.domain.lending.LendingService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/counterparties")
public class CounterpartyController {

    private final LendingService lendingService;

    public CounterpartyController(LendingService lendingService) {
        this.lendingService = lendingService;
    }

    @GetMapping
    public Page<CounterpartyResponse> getCounterparties(
            @PageableDefault(size = 50, sort = "name", direction = Sort.Direction.ASC) Pageable pageable) {
        return lendingService.getCounterparties(pageable);
    }

    @PostMapping
    public CounterpartyResponse createCounterparty(@Valid @RequestBody CreateCounterpartyRequest req) {
        return lendingService.createCounterparty(req);
    }

    @PutMapping("/{id}")
    public CounterpartyResponse updateCounterparty(@PathVariable UUID id, @RequestBody UpdateCounterpartyRequest req) {
        return lendingService.updateCounterparty(id, req);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteCounterparty(@PathVariable UUID id) {
        lendingService.deleteCounterparty(id);
    }
}
