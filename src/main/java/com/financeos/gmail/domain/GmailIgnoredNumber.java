package com.financeos.gmail.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/** A (bank, last 4) the user chose to ignore in Gmail "Needs attention": mail that parks unresolved with it is archived at once. */
@Entity
@Table(name = "gmail_ignored_numbers")
@Getter
@Setter
@NoArgsConstructor
public class GmailIgnoredNumber {

    /** Stored bank id when the mail names no bank (an empty string is NULL in Oracle). */
    public static final String UNKNOWN_BANK = "unknown";

    @Id
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "id", length = 36)
    private UUID id;

    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "user_id", length = 36, nullable = false)
    private UUID userId;

    @Column(name = "bank_id", length = 40, nullable = false)
    private String bankId;

    @Column(name = "last4", length = 4, nullable = false)
    private String last4;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public GmailIgnoredNumber(UUID userId, String bankId, String last4) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.bankId = bankId == null || bankId.isBlank() ? UNKNOWN_BANK : bankId;
        this.last4 = last4;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
