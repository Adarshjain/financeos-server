package com.financeos.api.instrument;

import com.financeos.api.instrument.dto.CorporateActionPage;
import com.financeos.api.instrument.dto.CorporateActionPreviewResponse;
import com.financeos.api.instrument.dto.CorporateActionResponse;
import com.financeos.api.instrument.dto.CreateCorporateActionRequest;
import com.financeos.api.instrument.dto.UpdateCorporateActionRequest;
import com.financeos.domain.instrument.corporateaction.CorporateActionPageService;
import com.financeos.domain.instrument.corporateaction.CorporateActionPreviewService;
import com.financeos.domain.instrument.corporateaction.CorporateActionService;
import com.financeos.domain.instrument.corporateaction.CorporateActionType;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
public class CorporateActionController {

    private final CorporateActionService corporateActionService;

    private final CorporateActionPageService pageService;
    private final CorporateActionPreviewService previewService;

    public CorporateActionController(CorporateActionService corporateActionService,
                                     CorporateActionPageService pageService,
                                     CorporateActionPreviewService previewService) {
        this.corporateActionService = corporateActionService;
        this.pageService = pageService;
        this.previewService = previewService;
    }

    /**
     * One page of the caller's corporate actions: {@code type} filters by action type, {@code search}
     * matches the instrument's name or symbol (case-insensitive, blank = all), {@code sort} is
     * {@code newest} (ex-date, default), {@code oldest} or {@code name}; {@code page} from 0, {@code size}
     * default 25, at most 200. Each row carries the caller's open quantity just before and after the
     * ex-date. The unpaged list below is unchanged.
     */
    @GetMapping("/api/v1/corporate-actions/page")
    public CorporateActionPage getCorporateActionPage(
            @RequestParam(required = false) CorporateActionType type,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false, defaultValue = "0") int page,
            @RequestParam(required = false, defaultValue = "" + CorporateActionPageService.DEFAULT_PAGE_SIZE) int size) {
        return pageService.page(type, search, sort, page, size);
    }

    /**
     * What the action would do to each of the caller's holdings of the instrument (quantity and cost
     * before and after, shares received by a demerger / merger target); saves nothing.
     */
    @PostMapping("/api/v1/instruments/{instrumentId}/corporate-actions/preview")
    public CorporateActionPreviewResponse previewCorporateAction(
            @PathVariable UUID instrumentId,
            @Valid @RequestBody CreateCorporateActionRequest request) {
        return previewService.preview(instrumentId, request);
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
