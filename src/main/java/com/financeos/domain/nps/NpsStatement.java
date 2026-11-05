package com.financeos.domain.nps;

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

/** One imported NPS statement (an account's tier for a period) with the units it printed at both ends. */
@Entity
@Table(name = "nps_statement")
@Getter
@Setter
@NoArgsConstructor
@Filter(name = "userFilter", condition = "user_id = :userId")
public class NpsStatement {

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

    @Column(name = "period_start", nullable = false)
    private LocalDate periodStart;

    @Column(name = "period_end", nullable = false)
    private LocalDate periodEnd;

    @Column(length = 8)
    private String layout;

    @Column(name = "file_sha256", length = 64)
    private String fileSha256;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private NpsStatementSource source = NpsStatementSource.UPLOAD;

    @Column(name = "gmail_message_id", length = 200)
    private String gmailMessageId;

    @Column(name = "value", precision = 16, scale = 2)
    private BigDecimal value;

    @Column(name = "contribution_count")
    private Integer contributionCount;

    @Column(name = "total_contribution", precision = 16, scale = 2)
    private BigDecimal totalContribution;

    @Column(name = "total_withdrawal", precision = 16, scale = 2)
    private BigDecimal totalWithdrawal;

    @Column(name = "notional_gain", precision = 16, scale = 2)
    private BigDecimal notionalGain;

    @Column(precision = 8, scale = 2)
    private BigDecimal xirr;

    @Column(name = "units_mismatch", nullable = false)
    private boolean unitsMismatch;

    /** Protean sent the statement without figures (scheme migration months): recorded so Gmail does not retry it. */
    @Column(nullable = false)
    private boolean incomplete;

    /** The parse warnings, one per line. */
    @Lob
    @Column
    private String warnings;

    @Column(name = "imported_at", nullable = false)
    private Instant importedAt;

    @OneToMany(mappedBy = "statement", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<NpsStatementHolding> holdings = new ArrayList<>();

    public void addHolding(NpsStatementHolding holding) {
        holding.setStatement(this);
        holdings.add(holding);
    }

    public int warningCount() {
        return warnings == null || warnings.isBlank() ? 0 : warnings.split("\n").length;
    }

    @PrePersist
    protected void onCreate() {
        if (importedAt == null) {
            importedAt = Instant.now();
        }
    }
}
