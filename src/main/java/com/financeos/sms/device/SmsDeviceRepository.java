package com.financeos.sms.device;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SmsDeviceRepository extends JpaRepository<SmsDevice, UUID> {

    Optional<SmsDevice> findByTokenHash(String tokenHash);

    List<SmsDevice> findByUserIdOrderByPairedAtDesc(UUID userId);

    List<SmsDevice> findByUserIdAndInstallIdOrderByPairedAtDesc(UUID userId, String installId);

    Optional<SmsDevice> findByIdAndUserId(UUID id, UUID userId);

    @Modifying
    @Query("UPDATE SmsDevice d SET d.lastSeenAt = :now WHERE d.id = :id")
    int touch(@Param("id") UUID id, @Param("now") Instant now);

    @Modifying
    @Query("UPDATE SmsDevice d SET d.lastSummaryAt = :now WHERE d.id = :id")
    int markSummary(@Param("id") UUID id, @Param("now") Instant now);

    @Modifying
    @Query("UPDATE SmsDevice d SET d.lastSummaryAt = :now, d.skippedPersonal = :personal, d.skippedOtp = :otp, "
            + "d.skippedPromo = :promo, d.skippedUnknownSender = :unknown WHERE d.id = :id")
    int markSummary(@Param("id") UUID id, @Param("now") Instant now, @Param("personal") int personal,
                    @Param("otp") int otp, @Param("promo") int promo, @Param("unknown") int unknown);
}
