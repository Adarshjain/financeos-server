package com.financeos.domain.notification.feed;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** One row of the in-app notifications feed. Unique per (user, dedupe key), so a producer can fire twice safely. */
@Entity
@Table(name = "app_notifications",
        uniqueConstraints = @UniqueConstraint(name = "uq_app_notifications_user_key", columnNames = {"user_id", "dedupe_key"}))
@Getter
@Setter
@NoArgsConstructor
@Filter(name = "userFilter", condition = "user_id = :userId")
public class AppNotification {

    public static final int MAX_TITLE_LENGTH = 200;
    public static final int MAX_BODY_LENGTH = 500;
    public static final int MAX_KEY_LENGTH = 200;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(length = 36)
    private UUID id;

    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "user_id", nullable = false, length = 36)
    private UUID userId;

    @Column(nullable = false, length = 40)
    private String kind;

    @Column(nullable = false, length = MAX_TITLE_LENGTH)
    private String title;

    @Column(length = MAX_BODY_LENGTH)
    private String body;

    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "job_id", length = 36)
    private UUID jobId;

    @Column(name = "job_type", length = 40)
    private String jobType;

    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "account_id", length = 36)
    private UUID accountId;

    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "connection_id", length = 36)
    private UUID connectionId;

    @Column(name = "dedupe_key", nullable = false, length = MAX_KEY_LENGTH)
    private String dedupeKey;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** Null = unread. */
    @Column(name = "read_at")
    private Instant readAt;

    /** Null = still in the feed; set when the user clears it (purged a day later). */
    @Column(name = "cleared_at")
    private Instant clearedAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
