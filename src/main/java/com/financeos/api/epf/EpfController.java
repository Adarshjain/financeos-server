package com.financeos.api.epf;

import com.financeos.api.epf.dto.EpfDismissRequest;
import com.financeos.api.epf.dto.EpfEmployerRequest;
import com.financeos.api.epf.dto.EpfEmployerResponse;
import com.financeos.api.epf.dto.EpfEntryRequest;
import com.financeos.api.epf.dto.EpfEntryResponse;
import com.financeos.api.epf.dto.EpfLinkRequest;
import com.financeos.api.epf.dto.EpfSummaryResponse;
import com.financeos.api.epf.dto.EpfWithdrawalOption;
import com.financeos.domain.epf.EpfService;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/epf")
public class EpfController {

    private final EpfService epfService;

    public EpfController(EpfService epfService) {
        this.epfService = epfService;
    }

    @GetMapping("/summary")
    public EpfSummaryResponse getEpfSummary() {
        return epfService.getSummary();
    }

    @GetMapping("/employers")
    public List<EpfEmployerResponse> listEpfEmployers() {
        return epfService.getEmployers();
    }

    @PostMapping("/employers")
    @ResponseStatus(HttpStatus.CREATED)
    public EpfEmployerResponse createEpfEmployer(@RequestBody EpfEmployerRequest request) {
        return epfService.createEmployer(request);
    }

    @PutMapping("/employers/{id}")
    public EpfEmployerResponse updateEpfEmployer(@PathVariable UUID id, @RequestBody EpfEmployerRequest request) {
        return epfService.updateEmployer(id, request);
    }

    /** Financial years (start years, newest first) that have entries, for one employer or all. */
    @GetMapping("/fys")
    public List<Integer> listEpfFinancialYears(@RequestParam(required = false) UUID employerId) {
        return epfService.getFinancialYears(employerId);
    }

    @GetMapping("/entries")
    public Page<EpfEntryResponse> listEpfEntries(
            @RequestParam(required = false) UUID employerId,
            @RequestParam(required = false) Integer fy,
            @ParameterObject @PageableDefault(size = 25) Pageable pageable) {
        return epfService.getEntries(employerId, fy, pageable);
    }

    @GetMapping("/entries/{id}")
    public EpfEntryResponse getEpfEntry(@PathVariable UUID id) {
        return epfService.getEntry(id);
    }

    @PostMapping("/entries")
    @ResponseStatus(HttpStatus.CREATED)
    public EpfEntryResponse createEpfEntry(@RequestBody EpfEntryRequest request) {
        return epfService.createEntry(request);
    }

    @PutMapping("/entries/{id}")
    public EpfEntryResponse updateEpfEntry(@PathVariable UUID id, @RequestBody EpfEntryRequest request) {
        return epfService.updateEntry(id, request);
    }

    @DeleteMapping("/entries/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteEpfEntry(@PathVariable UUID id) {
        epfService.deleteEntry(id);
    }

    @PutMapping("/entries/{id}/transaction")
    public EpfEntryResponse linkEpfWithdrawalTransaction(@PathVariable UUID id, @RequestBody EpfLinkRequest request) {
        return epfService.linkTransaction(id, request);
    }

    @DeleteMapping("/entries/{id}/transaction")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unlinkEpfWithdrawalTransaction(@PathVariable UUID id) {
        epfService.unlinkTransaction(id);
    }

    @PostMapping("/entries/{id}/suggestion/dismiss")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void dismissEpfSuggestion(@PathVariable UUID id, @RequestBody EpfDismissRequest request) {
        epfService.dismissSuggestion(id, request);
    }

    /** Unlinked withdrawals, best fit for the given bank credit first. */
    @GetMapping("/withdrawals")
    public List<EpfWithdrawalOption> listEpfWithdrawals(@RequestParam(required = false) UUID forTransactionId) {
        return epfService.getWithdrawalOptions(forTransactionId);
    }
}
