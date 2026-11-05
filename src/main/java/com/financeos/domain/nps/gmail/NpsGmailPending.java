package com.financeos.domain.nps.gmail;

import com.financeos.core.crypto.EncryptedStringConverter;
import com.financeos.domain.user.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * An NPS statement mail that is held for the user: its password, or a go-ahead on a PRAN that is no account yet. It
 * points at the Gmail message (the PDF is fetched again when the statement is imported, never stored). A NEW_ACCOUNT
 * row keeps what the import needs without guessing: the full PRAN (encrypted), the holder, the period, the POP name
 * (for the sector guess) and the password that opened the PDF (encrypted). Rows are never deleted: the status says
 * PENDING / IGNORED / DONE.
 */
@Entity
@Table(name = "nps_gmail_pending")
@Getter
@Setter
@NoArgsConstructor
@Filter(name = "userFilter", condition = "user_id = :userId")
public class NpsGmailPending {

    public static final int MAX_ATTEMPTS = 3;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(length = 36)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    @JdbcTypeCode(SqlTypes.VARCHAR)
    private User user;

    @Column(name = "connection_id", length = 36)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    private UUID connectionId;

    @Column(name = "gmail_message_id", nullable = false, length = 200)
    private String gmailMessageId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private NpsGmailPendingReason reason;

    @Column(name = "pran_last4", length = 4)
    private String pranLast4;

    @Column(name = "holder_name", length = 200)
    private String holderName;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "pran_encrypted", length = 500)
    private String pran;

    @Column(name = "period_start")
    private LocalDate periodStart;

    @Column(name = "period_end")
    private LocalDate periodEnd;

    /** The point of presence that uploaded the statement's contributions (eNPS means an All Citizen account). */
    @Column(name = "pop_name", length = 200)
    private String popName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private NpsGmailPendingStatus status = NpsGmailPendingStatus.PENDING;

    /** Why the last import attempt of this held statement failed (the row stays PENDING). */
    @Column(name = "last_error", length = 300)
    private String lastError;

    /** Import attempts so far; a held statement that failed {@value #MAX_ATTEMPTS} times waits for the user's retry. */
    @Column(nullable = false)
    private int attempts;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "password_encrypted", length = 500)
    private String password;

    /** When a password attempt last tried this row (null = never); the unlock batch takes the least recently tried first. */
    @Column(name = "last_tried_at")
    private Instant lastTriedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
