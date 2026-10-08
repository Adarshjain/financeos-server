package com.financeos.domain.inbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * What the user did to one inbox row: snoozed it until a date, or dismissed it. The row itself
 * is never stored (the inbox is computed on read); the key is its stable identity.
 */
@Entity
@Table(name = "inbox_item_state",
        uniqueConstraints = @UniqueConstraint(name = "uq_inbox_item_state_user_key", columnNames = {"user_id", "item_key"}))
@Getter
@Setter
@NoArgsConstructor
@Filter(name = "userFilter", condition = "user_id = :userId")
public class InboxItemState {

    public static final int MAX_KEY_LENGTH = 200;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(length = 36)
    private UUID id;

    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "user_id", nullable = false, length = 36)
    private UUID userId;

    @Column(name = "item_key", nullable = false, length = MAX_KEY_LENGTH)
    private String itemKey;

    /** Hidden while today is before this business date; the row comes back on it. Null = not snoozed. */
    @Column(name = "snoozed_until")
    private LocalDate snoozedUntil;

    /** Hidden for good once set; null = not dismissed. */
    @Column(name = "dismissed_at")
    private Instant dismissedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public InboxItemState(UUID userId, String itemKey) {
        this.userId = userId;
        this.itemKey = itemKey;
    }

    /** Still hiding the row on {@code today}: dismissed, or snoozed to a date after today (it returns on that date). */
    public boolean hides(LocalDate today) {
        return dismissedAt != null || (snoozedUntil != null && today.isBefore(snoozedUntil));
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
