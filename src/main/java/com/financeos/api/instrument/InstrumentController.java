package com.financeos.api.instrument;

import com.financeos.api.instrument.dto.InstrumentRequest;
import com.financeos.api.instrument.dto.InstrumentResponse;
import com.financeos.api.instrument.dto.UpsertPriceRequest;
import com.financeos.domain.instrument.InstrumentService;
import com.financeos.domain.instrument.InstrumentType;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

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
}
