package com.financeos.api.nps;

import com.financeos.api.nps.dto.NpsAccountRequest;
import com.financeos.api.nps.dto.NpsAllocationRequest;
import com.financeos.api.nps.dto.NpsEntryRequest;
import com.financeos.api.nps.dto.NpsEntryResponse;
import com.financeos.api.nps.dto.NpsLinkRequest;
import com.financeos.api.nps.dto.NpsRecordRequest;
import com.financeos.api.nps.dto.NpsSchemeDetailResponse;
import com.financeos.api.nps.dto.NpsSchemeResponse;
import com.financeos.api.nps.dto.NpsSummaryResponse;
import com.financeos.domain.nps.NpsContributor;
import com.financeos.domain.nps.NpsEntryType;
import com.financeos.domain.nps.NpsSchemeTier;
import com.financeos.domain.nps.NpsService;
import com.financeos.domain.nps.NpsTier;
import com.financeos.domain.nps.NpsVariant;
import com.financeos.domain.nps.link.NpsLinkService;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/nps")
public class NpsController {

    private final NpsService npsService;
    private final NpsLinkService linkService;

    public NpsController(NpsService npsService, NpsLinkService linkService) {
        this.npsService = npsService;
        this.linkService = linkService;
    }

    @GetMapping("/summary")
    public NpsSummaryResponse getNpsSummary() {
        return npsService.getSummary();
    }

    @GetMapping("/entries")
    public Page<NpsEntryResponse> listNpsEntries(
            @RequestParam(required = false) UUID accountId,
            @RequestParam(required = false) NpsTier tier,
            @RequestParam(required = false) NpsEntryType type,
            @RequestParam(required = false) String schemeCode,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) NpsContributor contributor,
            @ParameterObject @PageableDefault(size = 25) Pageable pageable) {
        return npsService.getEntries(accountId, tier, type, schemeCode, from, to, contributor, pageable);
    }

    @GetMapping("/entries/{id}")
    public NpsEntryResponse getNpsEntry(@PathVariable UUID id) {
        return npsService.getEntry(id);
    }

    @PostMapping("/entries")
    @ResponseStatus(HttpStatus.CREATED)
    public NpsEntryResponse createNpsEntry(@RequestBody NpsEntryRequest request) {
        return npsService.createEntry(request);
    }

    @PutMapping("/entries/{id}")
    public NpsEntryResponse updateNpsEntry(@PathVariable UUID id, @RequestBody NpsEntryRequest request) {
        return npsService.updateEntry(id, request);
    }

    @DeleteMapping("/entries/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteNpsEntry(@PathVariable UUID id) {
        npsService.deleteEntry(id);
    }

    /** Links a contribution to the debit that paid it. */
    @PostMapping("/entries/{id}/transaction")
    public NpsEntryResponse linkNpsEntryTransaction(@PathVariable UUID id, @RequestBody NpsLinkRequest request) {
        return linkService.link(id, request.transactionId());
    }

    @DeleteMapping("/entries/{id}/transaction")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unlinkNpsEntryTransaction(@PathVariable UUID id) {
        linkService.unlink(id);
    }

    /** "Not this one": that payment is not this contribution. */
    @PostMapping("/entries/{id}/suggestion/dismiss")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void dismissNpsLinkSuggestion(@PathVariable UUID id, @RequestBody NpsLinkRequest request) {
        linkService.dismiss(id, request.transactionId());
    }

    /** Records a payment as an estimated own contribution linked to it. */
    @PostMapping("/transactions/{txnId}/record")
    @ResponseStatus(HttpStatus.CREATED)
    public NpsEntryResponse recordNpsPayment(@PathVariable UUID txnId, @RequestBody(required = false) NpsRecordRequest request) {
        return linkService.record(txnId, request == null ? null : request.accountId(), request == null ? null : request.tier());
    }

    /** "Not NPS": the payment stops being offered as a contribution to record. */
    @PostMapping("/transactions/{txnId}/not-nps")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markNotNps(@PathVariable UUID txnId) {
        linkService.notNps(txnId);
    }

    /** "Not NPS" on every payment behind the "Set up NPS?" suggestion. */
    @PostMapping("/setup/not-nps")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markSetupNotNps() {
        linkService.notNpsSetup();
    }

    @PostMapping("/accounts")
    @ResponseStatus(HttpStatus.CREATED)
    public NpsSummaryResponse.Account createNpsAccount(@RequestBody NpsAccountRequest request) {
        return npsService.createAccount(request);
    }

    @PutMapping("/accounts/{id}")
    public NpsSummaryResponse.Account updateNpsAccount(@PathVariable UUID id, @RequestBody NpsAccountRequest request) {
        return npsService.updateAccount(id, request);
    }

    @DeleteMapping("/accounts/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteNpsAccount(@PathVariable UUID id) {
        npsService.deleteAccount(id);
    }

    @PutMapping("/accounts/{id}/allocation")
    public NpsSummaryResponse.Account replaceNpsAllocation(@PathVariable UUID id,
                                                           @RequestBody NpsAllocationRequest request) {
        return npsService.replaceAllocation(id, request);
    }

    /** The scheme catalog for pickers; every filter is optional. */
    @GetMapping("/schemes")
    public List<NpsSchemeResponse> searchNpsSchemes(
            @RequestParam(required = false) String query,
            @RequestParam(required = false) NpsSchemeTier tier,
            @RequestParam(required = false) NpsVariant variant,
            @RequestParam(required = false) String pfm) {
        return npsService.searchSchemes(query, tier, variant, pfm);
    }

    @GetMapping("/schemes/{code}")
    public NpsSchemeDetailResponse getNpsScheme(@PathVariable String code) {
        return npsService.getScheme(code);
    }
}
