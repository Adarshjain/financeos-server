package com.financeos.gmail.domain;

import com.financeos.domain.account.Account;
import com.financeos.domain.transaction.Transaction;
import com.financeos.domain.user.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "gmail_processed_messages", uniqueConstraints = {
        @UniqueConstraint(name = "uk_gmail_proc", columnNames = {"connection_id", "gmail_message_id"})
})
@Getter
@Setter
@NoArgsConstructor
@Filter(name = "userFilter", condition = "user_id = :userId")
public class GmailProcessedMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(length = 36)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "connection_id", nullable = false)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    private GmailConnection connection;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    private User user;

    @Column(name = "gmail_message_id", nullable = false)
    private String gmailMessageId;

    // Every persisted ledger entry is an account-scoped fact (CREATED / RECONCILED /
    // SKIPPED_BEFORE_WATERMARK / CLEANED_UP); rows die with the account via ON DELETE CASCADE so a
    // re-added account can re-ingest its emails. Non-account outcomes are not persisted.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id")
    @JdbcTypeCode(SqlTypes.VARCHAR)
    private Account account;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private GmailProcessedStatus status;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "transaction_id")
    @JdbcTypeCode(SqlTypes.VARCHAR)
    private Transaction transaction;

    @Column(name = "internal_date")
    private Instant internalDate;

    @Column(name = "sender_address", length = 320)
    private String senderAddress;

    @Column(name = "subject", length = 500)
    private String subject;

    @Column(name = "extracted_last4", length = 4)
    private String extractedLast4;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount = 0;

    @Column(name = "next_retry_at")
    private Instant nextRetryAt;

    @Column(name = "discovered_at")
    private Instant discoveredAt;

    @Column(length = 2000)
    private String error;

    @Column(name = "processed_at")
    private Instant processedAt;

    /** When this row was part of a "needs attention" digest (GMAIL_ATTENTION marker); reset on retry. */
    @Column(name = "attention_notified_at")
    private Instant attentionNotifiedAt;

    /** When the user archived this attention item (hidden from the list, Inbox and digest); null when not archived. */
    @Column(name = "archived_at")
    private Instant archivedAt;

    /** Shadow classifier answer (nothing routes on it yet); see com.financeos.gmail.classify. */
    @Column(name = "classified_kind", length = 16)
    private String classifiedKind;

    @Column(name = "classified_subtype", length = 24)
    private String classifiedSubtype;

    @Column(name = "classified_confidence", length = 8)
    private String classifiedConfidence;

    @Column(name = "classified_bank", length = 32)
    private String classifiedBank;

    @Column(name = "classified_signals", length = 500)
    private String classifiedSignals;

    @Column(name = "classifier_version")
    private Integer classifierVersion;

    /** What the existing pipeline decided: STATEMENT / ALERT / OTHER; null when it never got that far. */
    @Column(name = "pipeline_kind", length = 16)
    private String pipelineKind;

    /** Shadow offline-extraction result next to the AI's (nothing routes on it); see com.financeos.gmail.extract. */
    @Column(name = "extract_tier", length = 10)
    private String extractTier;

    @Column(name = "extract_template", length = 64)
    private String extractTemplate;

    @Column(name = "extract_version")
    private Integer extractVersion;

    @Column(name = "extract_amount", precision = 19, scale = 4)
    private java.math.BigDecimal extractAmount;

    @Column(name = "extract_currency", length = 3)
    private String extractCurrency;

    @Column(name = "extract_direction", length = 8)
    private String extractDirection;

    @Column(name = "extract_date")
    private java.time.LocalDate extractDate;

    @Column(name = "extract_last4", length = 4)
    private String extractLast4;

    @Column(name = "extract_counterparty", length = 255)
    private String extractCounterparty;

    /** Fields where the engine and the AI differ, or ENGINE_ONLY / AI_ONLY / AI_FAILED; null when they agree. */
    @Column(name = "extract_mismatch", length = 100)
    private String extractMismatch;

    @Column(name = "extract_ai_provider", length = 64)
    private String extractAiProvider;

    /** Shadow merge check: an existing transaction that looks like this mail's twin from the other side (issuer vs provider). */
    @Column(name = "extract_merge_txn", length = 36)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    private UUID extractMergeTxn;

    /**
     * A status change means the message was retried or reactivated, so an archive no longer applies:
     * if it lands in "needs attention" again it is a fresh outcome and must show.
     */
    public void setStatus(GmailProcessedStatus status) {
        if (this.status != status) {
            this.archivedAt = null;
        }
        this.status = status;
    }

    @PrePersist
    protected void onCreate() {
        if (discoveredAt == null) {
            discoveredAt = Instant.now();
        }
    }
}
