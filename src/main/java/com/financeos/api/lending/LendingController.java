package com.financeos.api.lending;

import com.financeos.api.lending.dto.*;
import com.financeos.domain.lending.LendingService;
import com.financeos.domain.lending.LendingStatus;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/lendings")
public class LendingController {

    private final LendingService lendingService;

    public LendingController(LendingService lendingService) {
        this.lendingService = lendingService;
    }

    @PostMapping
    public LendingResponse createLending(@Valid @RequestBody CreateLendingRequest req) {
        return lendingService.createLending(req);
    }

    @GetMapping
    public Page<LendingResponse> getLendings(
            @RequestParam(required = false) UUID counterpartyId,
            @RequestParam(required = false) LendingStatus status,
            @PageableDefault(size = 50, sort = "lendDate", direction = Sort.Direction.DESC) Pageable pageable) {
        return lendingService.getLendings(counterpartyId, status, pageable);
    }

    @GetMapping("/{id}")
    public LendingResponse getLendingDetail(@PathVariable UUID id) {
        return lendingService.getLendingDetail(id);
    }

    @PutMapping("/{id}")
    public LendingResponse updateLending(@PathVariable UUID id, @RequestBody UpdateLendingRequest req) {
        return lendingService.updateLending(id, req);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteLending(@PathVariable UUID id) {
        lendingService.deleteLending(id);
    }

    @PostMapping("/{id}/repayments")
    public LendingRepaymentResponse addRepayment(@PathVariable UUID id, @Valid @RequestBody CreateLendingRepaymentRequest req) {
        return lendingService.addRepayment(id, req);
    }

    @DeleteMapping("/{id}/repayments/{repaymentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteRepayment(@PathVariable UUID id, @PathVariable UUID repaymentId) {
        lendingService.deleteRepayment(id, repaymentId);
    }

    @PostMapping("/{id}/write-off")
    @ResponseStatus(HttpStatus.OK)
    public void writeOffLending(@PathVariable UUID id) {
        lendingService.writeOffLending(id);
    }

    @PostMapping("/{id}/reopen")
    @ResponseStatus(HttpStatus.OK)
    public void reopenLending(@PathVariable UUID id) {
        lendingService.reopenLending(id);
    }
}
