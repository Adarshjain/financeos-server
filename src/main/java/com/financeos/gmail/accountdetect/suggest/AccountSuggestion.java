package com.financeos.gmail.accountdetect.suggest;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import com.financeos.core.time.AppTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One account or card found in the user's mail, keyed by (user, bank:kind:last4) where kind is the detected base kind
 * (BANK_ACCOUNT, CREDIT_CARD, DEBIT_CARD), so a card later recognised as an add-on or replacement keeps its row.
 * Facts only (bank id, last4, product name, limit bucket, lifecycle, dates, mail count); never mail content.
 */
@Entity
@Table(name = "account_suggestions")
@Getter
@Setter
@NoArgsConstructor
public class AccountSuggestion implements Persistable<UUID> {

    /** Column lengths: the planner skips or truncates anything longer so one odd entity never fails a whole apply. */
    public static final int BANK_MAX = 40;
    public static final int LAST4_MAX = 4;
    public static final int VARIANT_MAX = 150;
    public static final int VARIANT_CANDIDATES_MAX = 2000;
    public static final int LIMIT_BUCKET_MAX = 40;
    public static final int PASSWORD_RULE_MAX = 100;

    public enum Kind { BANK_ACCOUNT, CREDIT_CARD, DEBIT_CARD, ADDON_CARD, REPLACEMENT_CARD }

    public enum Band { FOUND, OLDER, MAYBE }

    public enum State { NEW, ADDED, NOT_MINE, BELONGS, MATCHED }

    @Id
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "id", length = 36)
    private UUID id;

    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "user_id", length = 36, nullable = false)
    private UUID userId;

    @Column(name = "suggestion_key", length = 80, nullable = false)
    private String key;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", length = 20, nullable = false)
    private Kind kind;

    @Column(name = "bank", length = BANK_MAX, nullable = false)
    private String bank;

    @Column(name = "last4", length = LAST4_MAX, nullable = false)
    private String last4;

    @Column(name = "variant", length = VARIANT_MAX)
    private String variant;

    @Column(name = "kind_conf", length = 10)
    private String kindConf;

    @Column(name = "variant_conf", length = 10)
    private String variantConf;

    /** JSON array of product names, or null. */
    @Column(name = "variant_candidates", length = VARIANT_CANDIDATES_MAX)
    private String variantCandidates;

    @Column(name = "limit_bucket", length = LIMIT_BUCKET_MAX)
    private String limitBucket;

    @Column(name = "limit_conf", length = 10)
    private String limitConf;

    /** The exact credit limit of the latest statement that printed one (only with limit confidence MEDIUM or better). */
    @Column(name = "limit_amount", precision = 15, scale = 2)
    private java.math.BigDecimal limitAmount;

    /** The rule (e.g. "NAME4U+DDMM") that opened this number's protected statements; the password itself is never stored. */
    @Column(name = "password_rule", length = PASSWORD_RULE_MAX)
    private String passwordRule;

    /** Statements of this number name a holder and none is the user. */
    @Column(name = "not_in_your_name", nullable = false)
    private boolean notInYourName;

    @Column(name = "lifecycle", length = 20)
    private String lifecycle;

    @Column(name = "lifecycle_conf", length = 10)
    private String lifecycleConf;

    @Column(name = "exists_conf", length = 10, nullable = false)
    private String existsConf;

    @Enumerated(EnumType.STRING)
    @Column(name = "band", length = 10, nullable = false)
    private Band band;

    @Column(name = "first_seen_on")
    private LocalDate firstSeenOn;

    @Column(name = "last_seen_on")
    private LocalDate lastSeenOn;

    @Column(name = "evidence_mails", nullable = false)
    private int evidenceMails;

    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "parent_account_id", length = 36)
    private UUID parentAccountId;

    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "parent_suggestion_id", length = 36)
    private UUID parentSuggestionId;

    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "candidate_account_id", length = 36)
    private UUID candidateAccountId;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", length = 10, nullable = false)
    private State state = State.NEW;

    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "resolved_account_id", length = 36)
    private UUID resolvedAccountId;

    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "resolved_card_id", length = 36)
    private UUID resolvedCardId;

    /** The user answered "no" to "Is this your...": no candidate is ever set on this row again. */
    @Column(name = "is_this_declined", nullable = false)
    private boolean thisDeclined;

    /** The user answered "not part of that account" to "Older number on X?": never linked to an account as an older number again. */
    @Column(name = "parent_declined", nullable = false)
    private boolean parentDeclined;

    @Column(name = "notified_at")
    private Instant notifiedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** True from creation until persisted or loaded, so saving a new row does not SELECT it first. */
    @Transient
    private boolean newRow;

    public AccountSuggestion(UUID userId, String key) {
        this.newRow = true;
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.key = key;
    }

    /** ADDED, NOT_MINE and BELONGS are the user's answers: no run ever changes them. */
    public boolean answered() {
        return state == State.ADDED || state == State.NOT_MINE || state == State.BELONGS;
    }

    @Override
    public boolean isNew() {
        return newRow;
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        newRow = false;
    }

    @PrePersist
    protected void onCreate() {
        Instant now = AppTime.nowInstant();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = AppTime.nowInstant();
    }
}
