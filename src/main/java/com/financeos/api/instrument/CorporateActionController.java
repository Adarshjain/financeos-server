package com.financeos.api.instrument;

import com.financeos.api.instrument.dto.CorporateActionResponse;
import com.financeos.api.instrument.dto.CreateCorporateActionRequest;
import com.financeos.api.instrument.dto.UpdateCorporateActionRequest;
import com.financeos.domain.instrument.corporateaction.CorporateActionService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
public class CorporateActionController {

    private final CorporateActionService corporateActionService;

    public CorporateActionController(CorporateActionService corporateActionService) {
        this.corporateActionService = corporateActionService;
    }

    @GetMapping("/api/v1/corporate-actions")
    public List<CorporateActionResponse> getAllCorporateActions() {
        return corporateActionService.getAllCorporateActions();
    }

    @PostMapping("/api/v1/instruments/{instrumentId}/corporate-actions")
    @ResponseStatus(HttpStatus.CREATED)
    public CorporateActionResponse createCorporateAction(
            @PathVariable UUID instrumentId,
            @Valid @RequestBody CreateCorporateActionRequest request) {
        return corporateActionService.createCorporateAction(instrumentId, request);
    }

    @PutMapping("/api/v1/instruments/{instrumentId}/corporate-actions/{id}")
    public CorporateActionResponse updateCorporateAction(
            @PathVariable UUID instrumentId,
            @PathVariable UUID id,
            @Valid @RequestBody UpdateCorporateActionRequest request) {
        return corporateActionService.updateCorporateAction(instrumentId, id, request);
    }

    @DeleteMapping("/api/v1/instruments/{instrumentId}/corporate-actions/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteCorporateAction(
            @PathVariable UUID instrumentId,
            @PathVariable UUID id) {
        corporateActionService.deleteCorporateAction(instrumentId, id);
    }

    @GetMapping("/api/v1/instruments/{instrumentId}/corporate-actions")
    public List<CorporateActionResponse> getCorporateActions(@PathVariable UUID instrumentId) {
        return corporateActionService.getCorporateActions(instrumentId);
    }
}
