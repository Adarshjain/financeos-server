package com.financeos.api.sms;

import com.financeos.api.sms.dto.SmsDtos.GoogleLoginRequest;
import com.financeos.api.sms.dto.SmsDtos.LoginRequest;
import com.financeos.api.sms.dto.SmsDtos.LoginResponse;
import com.financeos.sms.device.SmsDeviceLoginService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The phone (no session) signs in with the account's email and password, or a Google ID token, and gets a device token. */
@RestController
@RequestMapping("/api/v1/sms/login")
public class SmsLoginController {

    private final SmsDeviceLoginService logins;

    public SmsLoginController(SmsDeviceLoginService logins) {
        this.logins = logins;
    }

    @PostMapping
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(new LoginResponse(logins.loginWithPassword(request.email(), request.password(), request.deviceName())));
    }

    @PostMapping("/google")
    public ResponseEntity<LoginResponse> loginWithGoogle(@Valid @RequestBody GoogleLoginRequest request) {
        return ResponseEntity.ok(new LoginResponse(logins.loginWithGoogle(request.idToken(), request.deviceName())));
    }
}
