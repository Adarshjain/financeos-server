package com.financeos.gmail.domain;

import com.financeos.domain.user.User;
import com.financeos.gmail.ingest.GmailSender;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "gmail_sync_cursors", uniqueConstraints = {
        @UniqueConstraint(name = "uk_gsc_conn_sender", columnNames = {"connection_id", "sender_id"})
})
@Getter
@Setter
@NoArgsConstructor
public class GmailSyncCursor {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(length = 36)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "connection_id", nullable = false)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    private GmailConnection connection;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sender_id", nullable = false)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    private GmailSender sender;

    @Column(name = "last_listed_at", nullable = false)
    private Instant lastListedAt;

    @Column(name = "earliest_covered_at", nullable = false)
    private Instant earliestCoveredAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }
}
