package com.financeos.domain.notification;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One row per user: notification preferences plus this user's Web Push device subscriptions
 * (a JSON array — a handful of browsers, not a table's worth). Keyed directly by user id with
 * no entity association: nothing here needs the User row.
 */
@Entity
@Table(name = "user_notification_settings")
@Getter
@Setter
@NoArgsConstructor
@Filter(name = "userFilter", condition = "user_id = :userId")
public class UserNotificationSettings {

    public static final int DEFAULT_SEND_HOUR = 9;
    public static final String DEFAULT_OFFSETS = "7,3,1,0";

    @Id
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "user_id", length = 36)
    private UUID userId;

    @Column(name = "push_enabled", nullable = false)
    private Boolean pushEnabled = true;

    /** Hour of day (business zone) from which scheduled reminders may go out. */
    @Column(name = "send_hour", nullable = false)
    private Integer sendHour = DEFAULT_SEND_HOUR;

    /** Days-before-due at which to remind, CSV, descending (e.g. {@code 7,3,1,0}). */
    @Column(name = "reminder_offsets", nullable = false, length = 40)
    private String reminderOffsets = DEFAULT_OFFSETS;

    /** {@code {"STATEMENT_RECEIVED":true,...}} — absent kinds use their default. */
    @Lob
    @Column(name = "kinds_json")
    private String kindsJson;

    /** JSON array of {@link com.financeos.domain.notification.push.PushSubscription}. */
    @Lob
    @Column(name = "push_subscriptions")
    private String pushSubscriptions;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public UserNotificationSettings(UUID userId) {
        this.userId = userId;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
