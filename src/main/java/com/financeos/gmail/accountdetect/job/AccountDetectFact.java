package com.financeos.gmail.accountdetect.job;

import com.financeos.core.crypto.EncryptedStringConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * One scanned mail's derived detector features ({@code facts}, encrypted JSON, see {@link FactsCodec}) and the
 * bookkeeping of its statement PDF. Never subject, snippet, body or PDF text.
 */
@Entity
@Table(name = "account_detect_facts", uniqueConstraints = {
        @UniqueConstraint(name = "uq_acct_detect_facts_msg", columnNames = {"user_id", "message_id"})
})
@Getter
@Setter
@NoArgsConstructor
public class AccountDetectFact {

    public enum UnlockState { NONE, LOCKED, UNLOCKED, FAILED }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(length = 36)
    private UUID id;

    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "user_id", length = 36, nullable = false)
    private UUID userId;

    @Column(name = "message_id", length = 64, nullable = false)
    private String messageId;

    @Column(name = "internal_date")
    private Instant internalDate;

    @Column(name = "sender_domain", length = 255)
    private String senderDomain;

    @Column(name = "role", length = 30)
    private String role;

    @Lob
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "facts", nullable = false)
    private String facts;

    @Column(name = "has_pdf", nullable = false)
    private Boolean hasPdf = false;

    @Column(name = "pdf_locked", nullable = false)
    private Boolean pdfLocked = false;

    @Column(name = "pdf_attachment_id", length = 4000)
    private String pdfAttachmentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "unlock_state", nullable = false, length = 20)
    private UnlockState unlockState = UnlockState.NONE;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
