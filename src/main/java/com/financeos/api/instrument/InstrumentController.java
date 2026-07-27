package com.financeos.api.instrument;

import com.financeos.api.instrument.dto.*;
import com.financeos.domain.instrument.InstrumentService;
import com.financeos.domain.instrument.InstrumentType;
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

    public InstrumentController(InstrumentService instrumentService) {
        this.instrumentService = instrumentService;
    }

    @GetMapping
    public List<InstrumentResponse> search(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) InstrumentType type) {
        return instrumentService.searchInstruments(search, type);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public InstrumentResponse create(@Valid @RequestBody InstrumentRequest request) {
        return instrumentService.createInstrument(request);
    }

    @GetMapping("/{id}")
    public InstrumentResponse getById(@PathVariable UUID id) {
        return instrumentService.getInstrumentById(id);
    }

    @PutMapping("/{id}")
    public InstrumentResponse update(@PathVariable UUID id, @Valid @RequestBody InstrumentRequest request) {
        return instrumentService.updateInstrument(id, request);
    }

    @PostMapping("/{id}/price")
    public InstrumentResponse upsertPrice(@PathVariable UUID id, @Valid @RequestBody UpsertPriceRequest request) {
        return instrumentService.upsertPrice(id, request);
    }

    @PutMapping("/{instrumentId}/prices/{priceId}")
    public InstrumentResponse updateManualPrice(
            @PathVariable UUID instrumentId,
            @PathVariable UUID priceId,
            @Valid @RequestBody UpdatePriceRequest request) {
        return instrumentService.updateManualPrice(instrumentId, priceId, request.price());
    }

    @DeleteMapping("/{instrumentId}/prices/{priceId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteManualPrice(
            @PathVariable UUID instrumentId,
            @PathVariable UUID priceId) {
        instrumentService.deleteManualPrice(instrumentId, priceId);
    }

    @GetMapping("/{id}/prices")
    public List<InstrumentPriceResponse> getPriceHistory(
            @PathVariable UUID id,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return instrumentService.getPriceHistory(id, from, to);
    }
}
