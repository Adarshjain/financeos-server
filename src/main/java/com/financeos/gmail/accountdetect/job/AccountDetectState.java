package com.financeos.gmail.accountdetect.job;

import com.financeos.core.crypto.EncryptedStringConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/** One user's background account-detect scan: status, the newest mail read, progress and the layout-key salt. */
@Entity
@Table(name = "account_detect_state")
@Getter
@Setter
@NoArgsConstructor
public class AccountDetectState {

    public enum Status { IDLE, RUNNING, PAUSED_AUTH, FAILED }

    @Id
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "user_id", length = 36)
    private UUID userId;

    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "connection_id", length = 36)
    private UUID connectionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private Status status = Status.IDLE;

    /** Internal date of the newest mail read; later runs list from two days before it. */
    @Column(name = "marker_at")
    private Instant markerAt;

    @Column(name = "first_scan_done_at")
    private Instant firstScanDoneAt;

    @Column(name = "last_run_at")
    private Instant lastRunAt;

    /** A short code only (e.g. AUTH_ERROR, an exception class name); never a message. */
    @Column(name = "last_error", length = 40)
    private String lastError;

    @Column(name = "mails_seen", nullable = false)
    private int mailsSeen;

    @Column(name = "mails_total", nullable = false)
    private int mailsTotal;

    /** Salt for the hashed layout and slot keys, so stored facts from different runs share keys. */
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "key_salt", length = 200)
    private String keySalt;

    /**
     * Generation of the user's unlock-retry requests: a trigger (name, birth date or saved password changed) adds one. A locked
     * fact whose {@code unlock_tried_gen} is below it is still to be tried. Written only by the repository's HQL update (never
     * by saving the entity), so a running scan saving its own copy of the state cannot lose a request made meanwhile.
     */
    @Column(name = "unlock_gen", nullable = false, insertable = false, updatable = false, columnDefinition = "NUMBER(10) DEFAULT 0 NOT NULL")
    private int unlockGen;

    /** Same for name changes: an opened fact whose {@code holder_checked_gen} is below it has its holder flag recomputed. */
    @Column(name = "holder_gen", nullable = false, insertable = false, updatable = false, columnDefinition = "NUMBER(10) DEFAULT 0 NOT NULL")
    private int holderGen;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public AccountDetectState(UUID userId) {
        this.userId = userId;
    }

    @PrePersist
    @PreUpdate
    protected void touch() {
        updatedAt = Instant.now();
    }
}
