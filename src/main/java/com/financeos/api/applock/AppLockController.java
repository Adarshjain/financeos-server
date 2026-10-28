package com.financeos.api.applock;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.api.applock.dto.AppLockStatusResponse;
import com.financeos.api.applock.dto.DisableAppLockRequest;
import com.financeos.api.applock.dto.RegistrationOptionsRequest;
import com.financeos.api.applock.dto.RegistrationVerifyRequest;
import com.financeos.api.applock.dto.RegistrationVerifyResponse;
import com.financeos.api.applock.dto.UnlockVerifyRequest;
import com.financeos.api.applock.dto.UpdateAppLockSettingsRequest;
import com.financeos.core.applock.AppLockProperties;
import com.financeos.core.applock.AppLockService;
import com.financeos.core.applock.AppLockSession;
import com.financeos.core.security.UserContext;
import com.financeos.domain.applock.AppLockDeviceService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * App lock: per-device passkey enrolment, unlock and the idle heartbeat. The lock itself is enforced by
 * {@link com.financeos.core.applock.AppLockFilter}; the registration/unlock options responses are the
 * standard WebAuthn {@code {publicKey: ...}} JSON.
 */
@RestController
@RequestMapping("/api/v1/app-lock")
public class AppLockController {

    private final AppLockService appLock;
    private final AppLockDeviceService deviceService;
    private final AppLockProperties props;
    private final ObjectMapper mapper;

    public AppLockController(AppLockService appLock, AppLockDeviceService deviceService, AppLockProperties props,
            ObjectMapper mapper) {
        this.appLock = appLock;
        this.deviceService = deviceService;
        this.props = props;
        this.mapper = mapper;
    }

    @GetMapping("/status")
    public AppLockStatusResponse status(HttpServletRequest request) {
        HttpSession session = request.getSession(true);
        boolean armed = appLock.isArmed(session);
        boolean locked = armed && appLock.isLocked(session);
        Integer after = armed
                ? (session.getAttribute(AppLockSession.AFTER_S) instanceof Number n ? n.intValue() : null)
                : null;
        return new AppLockStatusResponse(armed, locked, after, props.getRpId(), props.getHeartbeatSeconds());
    }

    @PostMapping("/registration/options")
    public ResponseEntity<JsonNode> registrationOptions(@Valid @RequestBody RegistrationOptionsRequest body,
            HttpServletRequest request) throws JsonProcessingException {
        String json = deviceService.startRegistration(request.getSession(true), UserContext.getCurrentUserId(),
                body.lockAfterSeconds());
        return ResponseEntity.ok(mapper.readTree(json));
    }

    @PostMapping("/registration/verify")
    public RegistrationVerifyResponse registrationVerify(@Valid @RequestBody RegistrationVerifyRequest body,
            HttpServletRequest request) {
        UUID deviceId = deviceService.finishRegistration(request.getSession(true), UserContext.getCurrentUserId(),
                body.credential(), body.label(), AppLockSession.deviceCookie(request));
        return new RegistrationVerifyResponse(deviceId.toString());
    }

    @PatchMapping("/settings")
    public ResponseEntity<Void> updateSettings(@Valid @RequestBody UpdateAppLockSettingsRequest body,
            HttpServletRequest request) {
        deviceService.updateSettings(request.getSession(true), UserContext.getCurrentUserId(),
                body.lockAfterSeconds());
        return ResponseEntity.noContent().build();
    }

    /** Needs a fresh assertion from {@code /unlock/options}; a locked session is refused by the filter. */
    @PostMapping("/disable")
    public ResponseEntity<Void> disable(@Valid @RequestBody DisableAppLockRequest body, HttpServletRequest request) {
        deviceService.disable(request.getSession(true), UserContext.getCurrentUserId(), body.credential());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/unlock/options")
    public ResponseEntity<JsonNode> unlockOptions(HttpServletRequest request) throws JsonProcessingException {
        String json = deviceService.startUnlock(request.getSession(true), UserContext.getCurrentUserId());
        return ResponseEntity.ok(mapper.readTree(json));
    }

    @PostMapping("/unlock/verify")
    public ResponseEntity<Void> unlockVerify(@Valid @RequestBody UnlockVerifyRequest body,
            HttpServletRequest request) {
        deviceService.finishUnlock(request.getSession(true), UserContext.getCurrentUserId(), body.credential());
        return ResponseEntity.noContent().build();
    }

    /** The idle heartbeat: the only request that counts as activity. A locked session is refused by the filter. */
    @PostMapping("/heartbeat")
    public ResponseEntity<Void> heartbeat(HttpServletRequest request) {
        HttpSession session = request.getSession(true);
        if (appLock.isArmed(session)) {
            appLock.heartbeat(session);
        }
        return ResponseEntity.noContent().build();
    }

    /** Locks now (the client's leave-the-app beacon). Idempotent; a no-op when app lock is off. */
    @PostMapping("/lock")
    public ResponseEntity<Void> lock(HttpServletRequest request) {
        appLock.lock(request.getSession(true));
        return ResponseEntity.noContent().build();
    }
}
