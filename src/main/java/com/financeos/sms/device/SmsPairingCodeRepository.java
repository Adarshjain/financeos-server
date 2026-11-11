package com.financeos.sms.device;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface SmsPairingCodeRepository extends JpaRepository<SmsPairingCode, String> {

    /** The atomic single-use claim: wins only for a code that is unused and not expired. */
    @Modifying
    @Query("UPDATE SmsPairingCode c SET c.usedAt = :now WHERE c.codeHash = :hash AND c.usedAt IS NULL AND c.expiresAt > :now")
    int claim(@Param("hash") String hash, @Param("now") Instant now);

    @Query("SELECT c.userId FROM SmsPairingCode c WHERE c.codeHash = :hash")
    Optional<UUID> findUserId(@Param("hash") String hash);

    @Modifying
    @Query("DELETE FROM SmsPairingCode c WHERE c.userId = :userId AND (c.usedAt IS NOT NULL OR c.expiresAt <= :now)")
    int deleteSpent(@Param("userId") UUID userId, @Param("now") Instant now);
}
