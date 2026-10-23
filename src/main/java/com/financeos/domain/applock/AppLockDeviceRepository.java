package com.financeos.domain.applock;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AppLockDeviceRepository extends JpaRepository<AppLockDevice, UUID> {

    Optional<AppLockDevice> findByIdAndUserId(UUID id, UUID userId);

    Optional<AppLockDevice> findByUserHandle(String userHandle);

    /** Credential ids are unique, so at most one row. */
    List<AppLockDevice> findByCredentialId(String credentialId);

    long deleteByIdAndUserId(UUID id, UUID userId);
}
