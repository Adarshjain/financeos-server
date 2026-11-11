package com.financeos.sms.device;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/** A paired phone. Only the SHA-256 hash of its token is stored. */
@Entity
@Table(name = "sms_devices")
@Getter
@Setter
@NoArgsConstructor
public class SmsDevice {

    @Id
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "id", length = 36)
    private UUID id;

    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "user_id", length = 36, nullable = false)
    private UUID userId;

    @Column(name = "name", length = 80, nullable = false)
    private String name;

    @Column(name = "token_hash", length = 64, nullable = false)
    private String tokenHash;

    @Column(name = "paired_at", nullable = false)
    private Instant pairedAt;

    @Column(name = "last_seen_at")
    private Instant lastSeenAt;

    @Column(name = "last_summary_at")
    private Instant lastSummaryAt;

    @Column(name = "paused", nullable = false)
    private boolean paused;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "skipped_personal", nullable = false)
    private int skippedPersonal;

    @Column(name = "skipped_otp", nullable = false)
    private int skippedOtp;

    @Column(name = "skipped_promo", nullable = false)
    private int skippedPromo;

    @Column(name = "skipped_unknown_sender", nullable = false)
    private int skippedUnknownSender;

    public boolean isRevoked() {
        return revokedAt != null;
    }
}
