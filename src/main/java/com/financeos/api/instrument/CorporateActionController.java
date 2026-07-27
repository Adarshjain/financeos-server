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
@RequestMapping("/api/v1/instruments/{instrumentId}/corporate-actions")
public class CorporateActionController {

    private final CorporateActionService corporateActionService;

    public CorporateActionController(CorporateActionService corporateActionService) {
        this.corporateActionService = corporateActionService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CorporateActionResponse createCorporateAction(
            @PathVariable UUID instrumentId,
            @Valid @RequestBody CreateCorporateActionRequest request) {
        return corporateActionService.createCorporateAction(instrumentId, request);
    }

    @PutMapping("/{id}")
    public CorporateActionResponse updateCorporateAction(
            @PathVariable UUID instrumentId,
            @PathVariable UUID id,
            @Valid @RequestBody UpdateCorporateActionRequest request) {
        return corporateActionService.updateCorporateAction(instrumentId, id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteCorporateAction(
            @PathVariable UUID instrumentId,
            @PathVariable UUID id) {
        corporateActionService.deleteCorporateAction(instrumentId, id);
    }

    @GetMapping
    public List<CorporateActionResponse> getCorporateActions(@PathVariable UUID instrumentId) {
        return corporateActionService.getCorporateActions(instrumentId);
    }
}
