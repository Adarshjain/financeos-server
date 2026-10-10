package com.financeos.api.instrument;

import com.financeos.api.instrument.dto.*;
import com.financeos.domain.instrument.Instrument;
import com.financeos.domain.instrument.InstrumentService;
import com.financeos.domain.instrument.InstrumentType;
import com.financeos.domain.instrument.search.InstrumentSearchService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/instruments")
public class InstrumentController {

    private final InstrumentService instrumentService;
    private final InstrumentSearchService instrumentSearchService;

    public InstrumentController(InstrumentService instrumentService, InstrumentSearchService instrumentSearchService) {
        this.instrumentService = instrumentService;
        this.instrumentSearchService = instrumentSearchService;
    }

    // Every instrument answer is as the signed-in user sees it: their own overrides of the display
    // fields and asset class applied, and the latest price they see (feed prices plus their own manual
    // ones). Nothing here changes what another user sees.

    @GetMapping("/catalog-search")
    public List<InstrumentCandidate> catalogSearch(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) InstrumentType type) {
        return instrumentSearchService.catalogSearch(q, type);
    }

    /**
     * The catalog instrument a picked search result names: the picked row, else the one with its
     * ISIN / AMFI code / Yahoo symbol / symbol + exchange, else a new catalog row. An existing row is
     * reused as it is — what a user sends never changes it.
     */
    @PostMapping("/resolve")
    public InstrumentResponse resolve(@Valid @RequestBody ResolveInstrumentRequest request) {
        Instrument instrument = instrumentSearchService.resolveInstrument(request,
                InstrumentSearchService.CatalogWrite.CREATE_ONLY);
        return instrumentService.getInstrumentById(instrument.getId());
    }

    /**
     * One page of the catalog instruments as the signed-in user sees them, with the total over all pages:
     * {@code search} matches the name and symbol they see, the catalog name and symbol, ISIN, AMFI code
     * and Yahoo symbol (blank = all); {@code type} is the type they see; {@code sort} is
     * {@code name[,asc|desc]} (the name they see, case-insensitive; default {@code name,asc}; anything
     * else is a 400); {@code page} from 0, {@code size} default 50, at most 200.
     */
    @GetMapping
    public InstrumentListPage search(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) InstrumentType type,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false, defaultValue = "0") int page,
            @RequestParam(required = false, defaultValue = "" + InstrumentService.DEFAULT_PAGE_SIZE) int size) {
        return instrumentService.listInstruments(search, type, sort, page, size);
    }

    /**
     * Adds an instrument to the shared catalog, or returns the existing one with its ISIN, AMFI code or
     * Yahoo symbol (no duplicate is minted).
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public InstrumentResponse create(@Valid @RequestBody InstrumentRequest request) {
        return ConcurrentWriteRetry.once(() -> instrumentService.createInstrument(request));
    }

    @GetMapping("/{id}")
    public InstrumentResponse getById(@PathVariable UUID id) {
        return instrumentService.getInstrumentById(id);
    }

    /**
     * Edits the instrument for the signed-in user's account only. Display fields (name, symbol,
     * exchange, currency, type) become their own overrides where they differ from the shared catalog.
     * A changed ISIN / AMFI code / Yahoo symbol switches only this user's holdings, trades, dividends,
     * SIPs and manual prices to the catalog instrument with those identifiers (added when none has
     * them) — the answer is then that instrument, with a different id. 400 when the identifiers lead
     * back to this same instrument (its price feed is shared), are all cleared, or would turn one of the
     * user's demergers / mergers into one from an instrument into itself. The user's own corporate
     * actions on or into the instrument move with them. Only the identifiers that changed pick the target; only the
     * display fields the user changed follow them there. {@code mergedHoldings} / {@code mergeNote} on
     * the answer say whether a holding was merged into one they already had (and whether realised gains
     * may change because both had sells).
     */
    @PutMapping("/{id}")
    public InstrumentResponse update(@PathVariable UUID id, @Valid @RequestBody InstrumentRequest request) {
        return ConcurrentWriteRetry.once(() -> instrumentService.updateInstrument(id, request));
    }

    /**
     * Pins the current user's own asset class for the instrument (reported as source MANUAL, to this
     * user only — the shared instrument and every other user are unaffected), or with
     * {@code assetClass: null} removes that override so the AMFI / name-rule class applies again.
     */
    @PatchMapping("/{id}")
    @Operation(operationId = "updateInstrumentAssetClass")
    public InstrumentResponse updateAssetClass(@PathVariable UUID id,
                                               @RequestBody UpdateInstrumentAssetClassRequest request) {
        return ConcurrentWriteRetry.once(() -> instrumentService.updateAssetClass(id, request.assetClass()));
    }

    /** Resets the instrument to the shared catalog for the signed-in user: removes all their overrides. */
    @DeleteMapping("/{id}/overrides")
    @Operation(operationId = "resetInstrumentOverrides")
    public InstrumentResponse resetOverrides(@PathVariable UUID id) {
        return instrumentService.resetOverrides(id);
    }

    /** Sets the signed-in user's own MANUAL price for a date (default today); no other user sees it. */
    @PostMapping("/{id}/price")
    public InstrumentResponse upsertPrice(@PathVariable UUID id, @Valid @RequestBody UpsertPriceRequest request) {
        return ConcurrentWriteRetry.once(() -> instrumentService.upsertPrice(id, request));
    }

    /** Edits one of the signed-in user's own MANUAL prices; 404 for any other price. */
    @PutMapping("/{instrumentId}/prices/{priceId}")
    public InstrumentResponse updateManualPrice(
            @PathVariable UUID instrumentId,
            @PathVariable UUID priceId,
            @Valid @RequestBody UpdatePriceRequest request) {
        return instrumentService.updateManualPrice(instrumentId, priceId, request.price());
    }

    /** Deletes one of the signed-in user's own MANUAL prices; 404 for any other price. */
    @DeleteMapping("/{instrumentId}/prices/{priceId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteManualPrice(
            @PathVariable UUID instrumentId,
            @PathVariable UUID priceId) {
        instrumentService.deleteManualPrice(instrumentId, priceId);
    }

    /** Feed prices plus the signed-in user's own MANUAL prices (theirs in place of the feed's on a shared date). */
    @GetMapping("/{id}/prices")
    public List<InstrumentPriceResponse> getPriceHistory(
            @PathVariable UUID id,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return instrumentService.getPriceHistory(id, from, to);
    }
}
