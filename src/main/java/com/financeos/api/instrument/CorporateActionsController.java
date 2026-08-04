package com.financeos.api.instrument;

import com.financeos.api.instrument.dto.CorporateActionResponse;
import com.financeos.domain.instrument.corporateaction.CorporateActionService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/corporate-actions")
public class CorporateActionsController {

    private final CorporateActionService corporateActionService;

    public CorporateActionsController(CorporateActionService corporateActionService) {
        this.corporateActionService = corporateActionService;
    }

    @GetMapping
    public List<CorporateActionResponse> getAllCorporateActions() {
        return corporateActionService.getAllCorporateActions();
    }
}
