package com.financeos.api.investment;

import com.financeos.api.investment.dto.CreateSipRequest;
import com.financeos.api.investment.dto.SipResponse;
import com.financeos.api.investment.dto.UpdateSipRequest;
import com.financeos.domain.investment.sip.SipService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/investments/sips")
public class SipController {

    private final SipService sipService;

    public SipController(SipService sipService) {
        this.sipService = sipService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SipResponse createSip(@Valid @RequestBody CreateSipRequest request) {
        return sipService.createSip(request);
    }

    @GetMapping
    public Page<SipResponse> getSips(
            @RequestParam(required = false) UUID brokerAccountId,
            @RequestParam(required = false) UUID instrumentId,
            @RequestParam(required = false) Boolean active,
            Pageable pageable) {
        return sipService.getSips(brokerAccountId, instrumentId, active, pageable);
    }

    @GetMapping("/{id}")
    public SipResponse getSip(@PathVariable UUID id) {
        return sipService.getSip(id);
    }

    @PutMapping("/{id}")
    public SipResponse updateSip(@PathVariable UUID id, @Valid @RequestBody UpdateSipRequest request) {
        return sipService.updateSip(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteSip(@PathVariable UUID id) {
        sipService.deleteSip(id);
    }
}
