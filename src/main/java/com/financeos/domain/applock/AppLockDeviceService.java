package com.financeos.domain.applock;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.financeos.core.applock.AppLockService;
import com.financeos.core.applock.AppLockSession;
import com.financeos.core.exception.ApiStatusException;
import com.financeos.core.exception.ValidationException;
import com.financeos.core.observability.Events;
import com.financeos.domain.user.User;
import com.financeos.domain.user.UserRepository;
import com.yubico.webauthn.AssertionRequest;
import com.yubico.webauthn.AssertionResult;
import com.yubico.webauthn.FinishAssertionOptions;
import com.yubico.webauthn.FinishRegistrationOptions;
import com.yubico.webauthn.RegistrationResult;
import com.yubico.webauthn.RelyingParty;
import com.yubico.webauthn.StartAssertionOptions;
import com.yubico.webauthn.StartRegistrationOptions;
import com.yubico.webauthn.data.AuthenticatorAttachment;
import com.yubico.webauthn.data.AuthenticatorSelectionCriteria;
import com.yubico.webauthn.data.AuthenticatorTransport;
import com.yubico.webauthn.data.ByteArray;
import com.yubico.webauthn.data.PublicKeyCredential;
import com.yubico.webauthn.data.PublicKeyCredentialCreationOptions;
import com.yubico.webauthn.data.ResidentKeyRequirement;
import com.yubico.webauthn.data.UserIdentity;
import com.yubico.webauthn.data.UserVerificationRequirement;
import jakarta.servlet.http.HttpSession;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.UUID;
import java.util.stream.Collectors;
import net.logstash.logback.argument.StructuredArguments;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Enrols, unlocks and removes this session's device passkey. Session state changes go through
 * {@link AppLockService}; this class owns the WebAuthn ceremonies and the device rows.
 */
@Service
@Transactional
public class AppLockDeviceService {

    private static final Logger log = LoggerFactory.getLogger(AppLockDeviceService.class);
    private static final long CEREMONY_TIMEOUT_MS = 60_000L;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final RelyingParty rp;
    private final AppLockService appLock;
    private final AppLockDeviceRepository devices;
    private final UserRepository users;
    private final ObjectMapper mapper;

    public AppLockDeviceService(RelyingParty rp, AppLockService appLock, AppLockDeviceRepository devices,
            UserRepository users, ObjectMapper mapper) {
        this.rp = rp;
        this.appLock = appLock;
        this.devices = devices;
        this.users = users;
        this.mapper = mapper;
    }

    /** Starts enrolment; returns the {@code {publicKey: ...}} JSON for navigator.credentials.create. */
    public String startRegistration(HttpSession session, UUID userId, int lockAfterSeconds) {
        requireAllowed(lockAfterSeconds);
        refuseWhileArmed(session, userId);
        User user = users.findById(userId).orElseThrow();
        UUID deviceId = UUID.randomUUID();
        byte[] handle = new byte[32];
        RANDOM.nextBytes(handle);
        PublicKeyCredentialCreationOptions options = rp.startRegistration(StartRegistrationOptions.builder()
                .user(UserIdentity.builder()
                        .name(user.getEmail())
                        .displayName("FinanceOS app lock")
                        .id(new ByteArray(handle))
                        .build())
                .authenticatorSelection(AuthenticatorSelectionCriteria.builder()
                        .authenticatorAttachment(AuthenticatorAttachment.PLATFORM)
                        .residentKey(ResidentKeyRequirement.DISCOURAGED)
                        .userVerification(UserVerificationRequirement.REQUIRED)
                        .build())
                .timeout(CEREMONY_TIMEOUT_MS)
                .build());
        try {
            ObjectNode pending = mapper.createObjectNode();
            pending.put("deviceId", deviceId.toString());
            pending.put("lockAfter", lockAfterSeconds);
            pending.put("options", options.toJson());
            appLock.putPending(session, AppLockSession.PENDING_REG, mapper.writeValueAsString(pending));
            return options.toCredentialsCreateJson();
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not start app lock enrolment", e);
        }
    }

    /** Verifies the new passkey, stores the device (replacing this session's old one) and arms the session. */
    public UUID finishRegistration(HttpSession session, UUID userId, JsonNode credential, String label,
            String replacedDeviceCookie) {
        String payload = appLock.takePending(session, AppLockSession.PENDING_REG);
        if (payload == null) {
            throw new ValidationException("App lock setup expired. Try again.");
        }
        RegistrationResult result;
        UUID deviceId;
        int lockAfter;
        ByteArray handle;
        String transports;
        try {
            JsonNode pending = mapper.readTree(payload);
            deviceId = UUID.fromString(pending.get("deviceId").asText());
            lockAfter = pending.get("lockAfter").asInt();
            PublicKeyCredentialCreationOptions options =
                    PublicKeyCredentialCreationOptions.fromJson(pending.get("options").asText());
            handle = options.getUser().getId();
            var response = PublicKeyCredential.parseRegistrationResponseJson(credential.toString());
            result = rp.finishRegistration(FinishRegistrationOptions.builder()
                    .request(options)
                    .response(response)
                    .build());
            if (!result.isUserVerified()) {
                throw new IllegalStateException("User verification missing");
            }
            transports = response.getResponse().getTransports().stream()
                    .map(AuthenticatorTransport::getId).collect(Collectors.joining(","));
        } catch (Exception e) {
            log.warn("App lock registration rejected: reason={}", e.getClass().getSimpleName());
            throw new ValidationException("Couldn't set up app lock. Try again.");
        }
        refuseWhileArmed(session, userId);
        return store(session, userId, deviceId, lockAfter, handle, result, label, transports, replacedDeviceCookie);
    }

    private UUID store(HttpSession session, UUID userId, UUID deviceId, int lockAfter, ByteArray handle,
            RegistrationResult result, String label, String transports, String replacedDeviceCookie) {
        replaceExisting(session, userId, replacedDeviceCookie);
        AppLockDevice device = new AppLockDevice();
        device.setId(deviceId);
        device.setUserId(userId);
        device.setUserHandle(handle.getBase64Url());
        device.setCredentialId(result.getKeyId().getId().getBase64Url());
        device.setPublicKeyCose(result.getPublicKeyCose().getBase64Url());
        device.setSignatureCount(result.getSignatureCount());
        device.setLockAfterSeconds(lockAfter);
        device.setLabel(label == null || label.isBlank() ? null : label.trim());
        device.setTransports(transports.isEmpty() ? null : transports);
        device.setBackupEligible(result.isBackupEligible());
        device.setBackedUp(result.isBackedUp());
        device.setCreatedAt(Instant.now(appLock.clock()));
        devices.saveAndFlush(device);
        appLock.arm(session, deviceId.toString(), lockAfter);
        log.info("App lock enrolled: deviceId={}", deviceId,
                StructuredArguments.keyValue("event", Events.APP_LOCK_ENROLLED),
                StructuredArguments.keyValue("deviceId", deviceId.toString()));
        return deviceId;
    }

    /**
     * An armed session whose device row still exists must turn app lock off (a fresh assertion) before enrolling
     * another passkey; otherwise re-enrolling would swap the key and sidestep that. A gone row may be re-enrolled.
     */
    private void refuseWhileArmed(HttpSession session, UUID userId) {
        UUID armed = parse(AppLockSession.deviceId(session));
        if (armed != null && devices.findByIdAndUserId(armed, userId).isPresent()) {
            throw new ApiStatusException(HttpStatus.CONFLICT, "APP_LOCK_ALREADY_ON", "App lock is already on");
        }
    }

    /** Re-enrolling on a device that already has a row replaces it (the session's armed device, or the device cookie's). */
    private void replaceExisting(HttpSession session, UUID userId, String deviceCookie) {
        for (String id : new String[] {AppLockSession.deviceId(session), deviceCookie}) {
            UUID parsed = parse(id);
            if (parsed != null) {
                devices.deleteByIdAndUserId(parsed, userId);
            }
        }
        devices.flush();
    }

    public void updateSettings(HttpSession session, UUID userId, int lockAfterSeconds) {
        requireAllowed(lockAfterSeconds);
        AppLockDevice device = armedDevice(session, userId);
        device.setLockAfterSeconds(lockAfterSeconds);
        devices.save(device);
        appLock.setLockAfter(session, lockAfterSeconds);
    }

    /**
     * Turns app lock off for this device: needs a fresh passkey assertion (from {@link #startUnlock}), then deletes
     * the row and disarms the session. An unarmed session, or one whose row is already gone, has nothing to protect.
     */
    public void disable(HttpSession session, UUID userId, JsonNode credential) {
        UUID deviceId = parse(AppLockSession.deviceId(session));
        AppLockDevice device = deviceId == null ? null : devices.findByIdAndUserId(deviceId, userId).orElse(null);
        if (device == null) {
            appLock.disarm(session);
            return;
        }
        if (!verifyAssertion(session, userId, device, credential)) {
            throw failedVerification(session, device);
        }
        devices.delete(device);
        log.info("App lock disabled: deviceId={}", deviceId,
                StructuredArguments.keyValue("event", Events.APP_LOCK_DISABLED),
                StructuredArguments.keyValue("deviceId", deviceId.toString()));
        appLock.disarm(session);
    }

    /** Starts an unlock; returns the {@code {publicKey: ...}} JSON for navigator.credentials.get. */
    public String startUnlock(HttpSession session, UUID userId) {
        AppLockDevice device = armedDevice(session, userId);
        AssertionRequest request = rp.startAssertion(StartAssertionOptions.builder()
                .username(device.getId().toString())
                .userVerification(UserVerificationRequirement.REQUIRED)
                .timeout(CEREMONY_TIMEOUT_MS)
                .build());
        try {
            appLock.putPending(session, AppLockSession.PENDING_ASSERT, request.toJson());
            return request.toCredentialsGetJson();
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not start app lock unlock", e);
        }
    }

    /** Verifies an unlock assertion. Any failure counts; the configured limit ends the session. */
    public void finishUnlock(HttpSession session, UUID userId, JsonNode credential) {
        AppLockDevice device = armedDevice(session, userId);
        if (!verifyAssertion(session, userId, device, credential)) {
            throw failedVerification(session, device);
        }
        appLock.unlock(session);
        log.info("App lock unlocked: deviceId={}", device.getId(),
                StructuredArguments.keyValue("event", Events.APP_LOCK_UNLOCKED),
                StructuredArguments.keyValue("deviceId", device.getId().toString()));
    }

    /** Counts a failed assertion; at the configured limit ends the session (401), otherwise 403. */
    private ApiStatusException failedVerification(HttpSession session, AppLockDevice device) {
        int fails = appLock.recordFailure(session);
        log.warn("App lock unlock failed: deviceId={}, failures={}", device.getId(), fails,
                StructuredArguments.keyValue("event", Events.APP_LOCK_UNLOCK_FAILED),
                StructuredArguments.keyValue("deviceId", device.getId().toString()),
                StructuredArguments.keyValue("failures", fails));
        if (appLock.failureLimitReached(fails)) {
            log.warn("App lock ended the session after too many failed unlocks",
                    StructuredArguments.keyValue("event", Events.APP_LOCK_SESSION_ENDED));
            session.invalidate();
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
            return new ApiStatusException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Signed out");
        }
        return new ApiStatusException(HttpStatus.FORBIDDEN, "APP_LOCK_UNLOCK_FAILED", "Couldn't verify. Try again.");
    }

    private boolean verifyAssertion(HttpSession session, UUID userId, AppLockDevice device, JsonNode credential) {
        String payload = appLock.takePending(session, AppLockSession.PENDING_ASSERT);
        if (payload == null) {
            return false;
        }
        try {
            AssertionRequest request = AssertionRequest.fromJson(payload);
            AssertionResult result = rp.finishAssertion(FinishAssertionOptions.builder()
                    .request(request)
                    .response(PublicKeyCredential.parseAssertionResponseJson(credential.toString()))
                    .build());
            if (!result.isSuccess() || !result.isUserVerified() || !result.isSignatureCounterValid()
                    || !device.getUserId().equals(userId)
                    || !device.getId().toString().equals(result.getUsername())
                    || !device.getCredentialId().equals(result.getCredential().getCredentialId().getBase64Url())
                    || !device.getUserHandle().equals(result.getCredential().getUserHandle().getBase64Url())) {
                return false;
            }
            device.setSignatureCount(result.getSignatureCount());
            device.setLastUsedAt(Instant.now(appLock.clock()));
            devices.save(device);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** This session's armed device row; 409 when the session is unarmed or the row is gone. */
    private AppLockDevice armedDevice(HttpSession session, UUID userId) {
        UUID deviceId = parse(AppLockSession.deviceId(session));
        if (deviceId == null) {
            throw new ApiStatusException(HttpStatus.CONFLICT, "APP_LOCK_NOT_ENABLED", "App lock is not on");
        }
        return devices.findByIdAndUserId(deviceId, userId)
                .orElseThrow(() -> new ApiStatusException(HttpStatus.CONFLICT, "APP_LOCK_DEVICE_MISSING",
                        "This device's app lock is gone. Sign out and sign in again."));
    }

    private void requireAllowed(int lockAfterSeconds) {
        if (!appLock.props().isAllowedLockAfter(lockAfterSeconds)) {
            throw new ValidationException("Lock after must be one of " + appLock.props().getAllowedLockAfterSeconds());
        }
    }

    private static UUID parse(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
