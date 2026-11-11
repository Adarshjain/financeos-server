package com.financeos.sms.line;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import com.financeos.core.time.AppTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One thing a phone receives messages from: a sender header with ending digits, or (numberless wallets) a sender with a
 * message family. Unique per user on (sender, ending, family). Starts OFF and unseen; only the user turns it on.
 */
@Entity
@Table(name = "sms_lines")
@Getter
@Setter
@NoArgsConstructor
public class SmsLine {

    @Id
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "id", length = 36)
    private UUID id;

    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "user_id", length = 36, nullable = false)
    private UUID userId;

    @Column(name = "sender", length = 20, nullable = false)
    private String sender;

    @Column(name = "ending", length = 4)
    private String ending;

    @Column(name = "family", length = 40)
    private String family;

    @Column(name = "provider", length = 80, nullable = false)
    private String provider;

    @Column(name = "msg_count", nullable = false)
    private int msgCount;

    @Column(name = "txn_count", nullable = false)
    private int txnCount;

    @Column(name = "first_msg_at")
    private Instant firstMsgAt;

    @Column(name = "last_msg_at")
    private Instant lastMsgAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", length = 10, nullable = false)
    private SmsLineState state = SmsLineState.OFF;

    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "account_id", length = 36)
    private UUID accountId;

    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "suggested_account_id", length = 36)
    private UUID suggestedAccountId;

    @Column(name = "sync_from")
    private LocalDate syncFrom;

    @Column(name = "seen_by_user", nullable = false)
    private boolean seenByUser;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = AppTime.nowInstant();
        if (id == null) id = UUID.randomUUID();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = AppTime.nowInstant();
    }
}
