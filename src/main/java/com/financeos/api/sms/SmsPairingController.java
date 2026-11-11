package com.financeos.api.sms;

import com.financeos.api.sms.dto.SmsDtos.PairRequest;
import com.financeos.api.sms.dto.SmsDtos.PairResponse;
import com.financeos.api.sms.dto.SmsDtos.PairingCodeResponse;
import com.financeos.core.security.UserContext;
import com.financeos.sms.device.SmsDeviceService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

/** Pairing a phone: the signed-in user asks for a code; the phone (no session) trades it for a device token. */
@RestController
@RequestMapping("/api/v1/sms")
public class SmsPairingController {

    private final SmsDeviceService devices;

    public SmsPairingController(SmsDeviceService devices) {
        this.devices = devices;
    }

    @PostMapping("/pairing-code")
    public ResponseEntity<PairingCodeResponse> pairingCode() {
        UUID userId = UserContext.getCurrentUserId();
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User is not authenticated");
        }
        SmsDeviceService.IssuedCode issued = devices.issueCode(userId);
        return ResponseEntity.ok(new PairingCodeResponse(issued.code(), issued.expiresAt()));
    }

    @PostMapping("/pair")
    public ResponseEntity<PairResponse> pair(@Valid @RequestBody PairRequest request) {
        return ResponseEntity.ok(new PairResponse(devices.pair(request.code(), request.deviceName())));
    }
}
