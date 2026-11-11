package com.financeos.sms.device;

import com.financeos.core.exception.ResourceNotFoundException;
import com.financeos.core.time.AppTime;
import com.financeos.domain.user.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Pairing codes, device tokens, and the user's control over their devices. */
@Service
public class SmsDeviceService {

    public static final Duration CODE_VALIDITY = Duration.ofMinutes(10);

    /** A device that presented a valid token: who it acts for. */
    public record DeviceAuth(UUID deviceId, UUID userId, String userEmail) {}

    public record IssuedCode(String code, Instant expiresAt) {}

    private final SmsDeviceRepository devices;
    private final SmsPairingCodeRepository codes;
    private final UserRepository users;
    private final PairAttemptLimiter limiter;

    public SmsDeviceService(SmsDeviceRepository devices, SmsPairingCodeRepository codes, UserRepository users,
                            PairAttemptLimiter limiter) {
        this.devices = devices;
        this.codes = codes;
        this.users = users;
        this.limiter = limiter;
    }

    @Transactional
    public IssuedCode issueCode(UUID userId) {
        Instant now = AppTime.nowInstant();
        codes.deleteSpent(userId, now);
        String code = SmsTokens.newCode();
        SmsPairingCode row = new SmsPairingCode();
        row.setCodeHash(SmsTokens.sha256(code));
        row.setUserId(userId);
        row.setExpiresAt(now.plus(CODE_VALIDITY));
        codes.save(row);
        return new IssuedCode(code, row.getExpiresAt());
    }

    /** Spends the code and creates the device; returns the token, shown once. Any failure looks the same. */
    @Transactional
    public String pair(String rawCode, String deviceName) {
        limiter.assertNotLockedOut();
        Instant now = AppTime.nowInstant();
        String hash = SmsTokens.sha256(SmsTokens.normaliseCode(rawCode));
        Optional<UUID> userId = codes.findUserId(hash);
        if (userId.isEmpty() || codes.claim(hash, now) != 1) {
            limiter.recordFailure();
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid or expired code");
        }
        String token = SmsTokens.newToken();
        SmsDevice device = new SmsDevice();
        device.setId(UUID.randomUUID());
        device.setUserId(userId.get());
        device.setName(deviceName.trim());
        device.setTokenHash(SmsTokens.sha256(token));
        device.setPairedAt(now);
        devices.save(device);
        return token;
    }

    /** The device behind a bearer token (not revoked), with its last-seen time updated. */
    @Transactional
    public Optional<DeviceAuth> authenticate(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        Optional<SmsDevice> device = devices.findByTokenHash(SmsTokens.sha256(token.trim())).filter(d -> !d.isRevoked());
        if (device.isEmpty()) {
            return Optional.empty();
        }
        return users.findById(device.get().getUserId()).map(user -> {
            devices.touch(device.get().getId(), AppTime.nowInstant());
            return new DeviceAuth(device.get().getId(), user.getId(), user.getEmail());
        });
    }

    @Transactional(readOnly = true)
    public SmsDevice requireDevice(UUID deviceId) {
        return devices.findById(deviceId).orElseThrow(() -> new ResourceNotFoundException("Device", deviceId));
    }

    @Transactional(readOnly = true)
    public List<SmsDevice> list(UUID userId) {
        return devices.findByUserIdOrderByPairedAtDesc(userId);
    }

    @Transactional
    public void pause(UUID userId, UUID id) {
        owned(userId, id).setPaused(true);
    }

    @Transactional
    public void resume(UUID userId, UUID id) {
        owned(userId, id).setPaused(false);
    }

    @Transactional
    public void revoke(UUID userId, UUID id) {
        SmsDevice device = owned(userId, id);
        if (device.getRevokedAt() == null) {
            device.setRevokedAt(AppTime.nowInstant());
        }
    }

    @Transactional
    public void markSummary(UUID deviceId) {
        devices.markSummary(deviceId, AppTime.nowInstant());
    }

    /** Records a summary call together with the phone's skipped counts. */
    @Transactional
    public void markSummary(UUID deviceId, int personal, int otp, int promo, int unknownSender) {
        devices.markSummary(deviceId, AppTime.nowInstant(), Math.max(0, personal), Math.max(0, otp), Math.max(0, promo),
                Math.max(0, unknownSender));
    }

    private SmsDevice owned(UUID userId, UUID id) {
        return devices.findByIdAndUserId(id, userId).orElseThrow(() -> new ResourceNotFoundException("Device", id));
    }
}
