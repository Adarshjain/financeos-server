package com.financeos.domain.applock;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** One enrolled device: its passkey's public key and the lock-after setting. Binary values are base64url text. */
@Entity
@Table(name = "app_lock_devices")
@Getter
@Setter
@NoArgsConstructor
@Filter(name = "userFilter", condition = "user_id = :userId")
public class AppLockDevice {

    @Id
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(length = 36)
    private UUID id;

    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "user_id", nullable = false, length = 36)
    private UUID userId;

    @Column(name = "user_handle", nullable = false, length = 64)
    private String userHandle;

    @Column(name = "credential_id", nullable = false, length = 1400)
    private String credentialId;

    @Column(name = "public_key_cose", nullable = false, length = 2000)
    private String publicKeyCose;

    @Column(name = "signature_count", nullable = false)
    private long signatureCount;

    @Column(name = "lock_after_seconds", nullable = false)
    private int lockAfterSeconds;

    @Column(length = 100)
    private String label;

    /** Comma-separated WebAuthn transports, e.g. {@code internal}. */
    @Column(length = 200)
    private String transports;

    @Column(name = "backup_eligible")
    private Boolean backupEligible;

    @Column(name = "backed_up")
    private Boolean backedUp;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;
}
