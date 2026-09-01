package com.financeos.domain.statement;

import com.financeos.domain.account.Account;
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
import java.util.UUID;

@Entity
@Table(name = "statements")
@Getter
@Setter
@NoArgsConstructor
@Filter(name = "userFilter", condition = "user_id = :userId")
public class Statement {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(length = 36)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    @JdbcTypeCode(SqlTypes.VARCHAR)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id")
    @JdbcTypeCode(SqlTypes.VARCHAR)
    private Account account;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private StatementSource source;

    @Column(name = "source_ref")
    private String sourceRef;

    @Column(name = "file_sha256", length = 64)
    private String fileSha256;

    @Column(name = "statement_type", length = 20)
    private String statementType;

    @Column(name = "period_start")
    private LocalDate periodStart;

    @Column(name = "period_end")
    private LocalDate periodEnd;

    @Column(name = "opening_balance", precision = 19, scale = 4)
    private BigDecimal openingBalance;

    @Column(name = "closing_balance", precision = 19, scale = 4)
    private BigDecimal closingBalance;

    @Column(name = "bank_name")
    private String bankName;

    @Column(name = "account_number_masked", length = 40)
    private String accountNumberMasked;

    @Column(name = "transaction_count")
    private Integer transactionCount;

    @Column(name = "lines_skipped")
    private Integer linesSkipped;

    @Column(name = "total_debits", precision = 19, scale = 4)
    private BigDecimal totalDebits;

    @Column(name = "total_credits", precision = 19, scale = 4)
    private BigDecimal totalCredits;

    @Column(name = "parse_mode", length = 40)
    private String parseMode;

    @Column(name = "chain_validation_pct", precision = 5, scale = 2)
    private BigDecimal chainValidationPct;

    @Column(name = "checksum_ok")
    private Boolean checksumOk;

    @Enumerated(EnumType.STRING)
    @Column(name = "verdict")
    private StatementVerdict verdict;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @OneToOne(mappedBy = "statement", cascade = CascadeType.ALL, orphanRemoval = true)
    private StatementCreditCardDetails creditCardDetails;

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
