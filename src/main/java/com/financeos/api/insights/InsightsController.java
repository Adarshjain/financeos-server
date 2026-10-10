package com.financeos.api.insights;

import com.financeos.domain.insights.EmergencyFundService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Derived figures across the user's money (dashboard widgets). */
@RestController
public class InsightsController {

    private final EmergencyFundService emergencyFundService;

    public InsightsController(EmergencyFundService emergencyFundService) {
        this.emergencyFundService = emergencyFundService;
    }

    /** Liquid balance, the last six full months' outflow, its median and the months it covers. */
    @GetMapping("/api/v1/insights/emergency-fund")
    public ResponseEntity<EmergencyFundResponse> emergencyFund() {
        return ResponseEntity.ok(emergencyFundService.emergencyFund());
    }
}
