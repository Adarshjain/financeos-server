package com.financeos.api.nps;

import com.financeos.api.nps.dto.NpsGmailAccountRequest;
import com.financeos.api.nps.dto.NpsGmailDetectionAcceptedResponse;
import com.financeos.api.nps.dto.NpsGmailPasswordRequest;
import com.financeos.api.nps.dto.NpsGmailRetryResponse;
import com.financeos.api.nps.dto.NpsGmailPasswordResponse;
import com.financeos.domain.nps.gmail.NpsGmailDetectionService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** The user's answers to the NPS accounts Gmail found: go-ahead, ignore, and the password of locked statements. */
@RestController
@RequestMapping("/api/v1/nps/gmail-detections")
public class NpsGmailController {

    private final NpsGmailDetectionService detectionService;

    public NpsGmailController(NpsGmailDetectionService detectionService) {
        this.detectionService = detectionService;
    }

    /** Adds the detected PRAN as an account; its held statements import in the background. */
    @PostMapping("/{key}/account")
    public NpsGmailDetectionAcceptedResponse addNpsGmailDetectionAccount(@PathVariable UUID key,
                                                                         @RequestBody NpsGmailAccountRequest request) {
        NpsGmailDetectionService.Accepted accepted = detectionService.addAccount(key, request.relation(), request.sector());
        return new NpsGmailDetectionAcceptedResponse(accepted.accountId(), accepted.importing());
    }

    @DeleteMapping("/{key}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void ignoreNpsGmailDetection(@PathVariable UUID key) {
        detectionService.ignore(key);
    }

    /** Queues the import again of the held statements of the user's accounts that failed 3 times. */
    @PostMapping("/retry")
    public NpsGmailRetryResponse retryNpsGmailImports() {
        return new NpsGmailRetryResponse(detectionService.retryFailed());
    }

    /** Tries the password on every locked Gmail NPS statement. */
    @PostMapping("/password")
    public NpsGmailPasswordResponse unlockNpsGmailStatements(@RequestBody NpsGmailPasswordRequest request) {
        NpsGmailDetectionService.Unlocked unlocked = detectionService.unlock(request.password());
        return new NpsGmailPasswordResponse(unlocked.opened(), unlocked.stillLocked());
    }
}
