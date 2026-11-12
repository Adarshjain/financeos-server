package com.financeos.sms.device;

import com.financeos.core.exception.ResourceNotFoundException;
import com.financeos.core.time.AppTime;
import com.financeos.domain.user.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Device tokens, and the user's control over their devices. */
@Service
public class SmsDeviceService {

    /** A device that presented a valid token: who it acts for. */
    public record DeviceAuth(UUID deviceId, UUID userId, String userEmail) {}

    private final SmsDeviceRepository devices;
    private final UserRepository users;

    public SmsDeviceService(SmsDeviceRepository devices, UserRepository users) {
        this.devices = devices;
        this.users = users;
    }

    /**
     * Creates a device for the user; returns its token, shown once. Only the hash is stored. With an install id, the
     * user's existing device with that id is reused (new token, connected again) and its older duplicates are revoked.
     */
    @Transactional
    public String createDevice(UUID userId, String deviceName, String installId) {
        String token = SmsTokens.newToken();
        Instant now = AppTime.nowInstant();
        String install = installId == null || installId.isBlank() ? null : installId.trim();
        List<SmsDevice> existing = install == null ? List.of()
                : devices.findByUserIdAndInstallIdOrderByPairedAtDesc(userId, install);
        SmsDevice device;
        if (existing.isEmpty()) {
            device = new SmsDevice();
            device.setId(UUID.randomUUID());
            device.setUserId(userId);
            device.setInstallId(install);
        } else {
            device = existing.get(0);
            device.setRevokedAt(null);
            for (SmsDevice other : existing.subList(1, existing.size())) {
                if (other.getRevokedAt() == null) {
                    other.setRevokedAt(now);
                }
            }
        }
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
