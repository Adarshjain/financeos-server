package com.financeos.domain.nps;

import com.financeos.domain.transaction.Transaction;
import com.financeos.domain.user.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** One row of an NPS account's ledger; its units sit in the per-scheme {@link NpsEntryLine}s. */
@Entity
@Table(name = "nps_entry")
@Getter
@Setter
@NoArgsConstructor
@Filter(name = "userFilter", condition = "user_id = :userId")
public class NpsEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(length = 36)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    @JdbcTypeCode(SqlTypes.VARCHAR)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    private NpsAccount account;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 4)
    private NpsTier tier;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private NpsEntryType type;

    @Enumerated(EnumType.STRING)
    @Column(length = 8)
    private NpsContributor contributor;

    /** Signed: money in is positive, a withdrawal negative. */
    @Column(precision = 14, scale = 2)
    private BigDecimal amount;

    @Column(name = "posted_date", nullable = false)
    private LocalDate postedDate;

    @Column(name = "nav_date")
    private LocalDate navDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private NpsEntrySource source = NpsEntrySource.MANUAL;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private NpsEntryStatus status = NpsEntryStatus.CONFIRMED;

    @Column(length = 300)
    private String description;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "transaction_id")
    @JdbcTypeCode(SqlTypes.VARCHAR)
    private Transaction transaction;

    /** The statement this entry came from (the foreign key arrives with the statement phase). */
    @Column(name = "statement_id", length = 36)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    private UUID statementId;

    @Column(name = "dedupe_key", length = 200)
    private String dedupeKey;

    @Column(length = 500)
    private String note;

    @OneToMany(mappedBy = "entry", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<NpsEntryLine> lines = new ArrayList<>();

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public void addLine(NpsEntryLine line) {
        line.setEntry(this);
        lines.add(line);
    }

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }
}
