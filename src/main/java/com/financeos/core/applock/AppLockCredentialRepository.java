package com.financeos.core.applock;

import com.financeos.domain.applock.AppLockDevice;
import com.financeos.domain.applock.AppLockDeviceRepository;
import com.yubico.webauthn.CredentialRepository;
import com.yubico.webauthn.RegisteredCredential;
import com.yubico.webauthn.data.AuthenticatorTransport;
import com.yubico.webauthn.data.ByteArray;
import com.yubico.webauthn.data.PublicKeyCredentialDescriptor;
import com.yubico.webauthn.data.exception.Base64UrlException;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Feeds the relying party from {@code app_lock_devices}. The WebAuthn "username" is the internal device
 * id and the user handle is the device's own random handle (never the user id), so every device is its
 * own passkey identity.
 */
@Component
public class AppLockCredentialRepository implements CredentialRepository {

    private final AppLockDeviceRepository devices;

    public AppLockCredentialRepository(AppLockDeviceRepository devices) {
        this.devices = devices;
    }

    @Override
    public Set<PublicKeyCredentialDescriptor> getCredentialIdsForUsername(String username) {
        return findDevice(username)
                .map(d -> Set.of(descriptor(d)))
                .orElse(Set.of());
    }

    @Override
    public Optional<ByteArray> getUserHandleForUsername(String username) {
        return findDevice(username).flatMap(d -> bytes(d.getUserHandle()));
    }

    @Override
    public Optional<String> getUsernameForUserHandle(ByteArray userHandle) {
        return devices.findByUserHandle(userHandle.getBase64Url()).map(d -> d.getId().toString());
    }

    @Override
    public Optional<RegisteredCredential> lookup(ByteArray credentialId, ByteArray userHandle) {
        return devices.findByCredentialId(credentialId.getBase64Url()).stream()
                .filter(d -> d.getUserHandle().equals(userHandle.getBase64Url()))
                .findFirst()
                .flatMap(AppLockCredentialRepository::credential);
    }

    @Override
    public Set<RegisteredCredential> lookupAll(ByteArray credentialId) {
        return devices.findByCredentialId(credentialId.getBase64Url()).stream()
                .map(AppLockCredentialRepository::credential)
                .flatMap(Optional::stream)
                .collect(Collectors.toSet());
    }

    private Optional<AppLockDevice> findDevice(String username) {
        try {
            return devices.findById(UUID.fromString(username));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static PublicKeyCredentialDescriptor descriptor(AppLockDevice d) {
        Set<AuthenticatorTransport> transports = new TreeSet<>();
        if (d.getTransports() != null && !d.getTransports().isBlank()) {
            Arrays.stream(d.getTransports().split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .map(AuthenticatorTransport::of)
                    .forEach(transports::add);
        }
        if (transports.isEmpty()) {
            transports.add(AuthenticatorTransport.INTERNAL);
        }
        return PublicKeyCredentialDescriptor.builder()
                .id(bytes(d.getCredentialId()).orElseThrow())
                .transports(transports)
                .build();
    }

    private static Optional<RegisteredCredential> credential(AppLockDevice d) {
        Optional<ByteArray> id = bytes(d.getCredentialId());
        Optional<ByteArray> handle = bytes(d.getUserHandle());
        Optional<ByteArray> key = bytes(d.getPublicKeyCose());
        if (id.isEmpty() || handle.isEmpty() || key.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(RegisteredCredential.builder()
                .credentialId(id.get())
                .userHandle(handle.get())
                .publicKeyCose(key.get())
                .signatureCount(d.getSignatureCount())
                .backupEligible(d.getBackupEligible())
                .backupState(d.getBackedUp())
                .build());
    }

    private static Optional<ByteArray> bytes(String base64Url) {
        try {
            return Optional.of(ByteArray.fromBase64Url(base64Url));
        } catch (Base64UrlException e) {
            return Optional.empty();
        }
    }
}
