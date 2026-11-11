package com.financeos.sms.ingest;

import com.financeos.core.crypto.EncryptedStringConverter;
import com.financeos.core.time.AppTime;
import com.financeos.domain.transaction.TransactionType;
import com.financeos.sms.read.SmsKind;
import com.financeos.sms.read.SmsReadStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** One message a phone sent for a line that is ON: its text (encrypted), what the reader made of it and what happens to it. */
@Entity
@Table(name = "sms_messages")
@Getter
@Setter
@NoArgsConstructor
public class SmsMessage {

    @Id
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "id", length = 36)
    private UUID id;

    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "user_id", length = 36, nullable = false)
    private UUID userId;

    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "device_id", length = 36)
    private UUID deviceId;

    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "line_id", length = 36)
    private UUID lineId;

    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "account_id", length = 36)
    private UUID accountId;

    @Column(name = "sender", length = 20, nullable = false)
    private String sender;

    @Lob
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "body", nullable = false)
    private String body;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    @Column(name = "sim_slot")
    private Integer simSlot;

    @Column(name = "fingerprint", length = 64, nullable = false)
    private String fingerprint;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", length = 30, nullable = false)
    private SmsKind kind = SmsKind.UNKNOWN;

    @Enumerated(EnumType.STRING)
    @Column(name = "read_status", length = 20, nullable = false)
    private SmsReadStatus readStatus = SmsReadStatus.IGNORED;

    @Column(name = "template_key", length = 40)
    private String templateKey;

    @Column(name = "amount", precision = 19, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "direction", length = 10)
    private TransactionType direction;

    @Column(name = "ending", length = 4)
    private String ending;

    @Column(name = "ref_no", length = 60)
    private String refNo;

    @Column(name = "balance_after", precision = 19, scale = 2)
    private BigDecimal balanceAfter;

    @Column(name = "counterparty", length = 200)
    private String counterparty;

    @Column(name = "txn_date")
    private LocalDate txnDate;

    /** CONFIRMED or REJECTED when the owner decided; a re-read keeps what they decided. */
    @Column(name = "user_decision", length = 10)
    private String userDecision;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", length = 30)
    private SmsOutcome outcome;

    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "duplicate_of", length = 36)
    private UUID duplicateOf;

    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "transaction_id", length = 36)
    private UUID transactionId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (id == null) id = UUID.randomUUID();
        if (createdAt == null) createdAt = AppTime.nowInstant();
    }
}
