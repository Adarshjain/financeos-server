package com.financeos.domain.loan;

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
@Table(name = "loans")
@Getter
@Setter
@NoArgsConstructor
@Filter(name = "userFilter", condition = "user_id = :userId")
public class Loan {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(length = 36)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    private User user;

    @Column(nullable = false, length = 200)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "loan_type", nullable = false, length = 30)
    private LoanType loanType;

    @Column(nullable = false, length = 200)
    private String lender;

    @Column(name = "loan_account_number", length = 100)
    private String loanAccountNumber;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "payment_account_id")
    @JdbcTypeCode(SqlTypes.VARCHAR)
    private Account paymentAccount;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal principal;

    @Column(name = "annual_rate_pct", nullable = false, precision = 9, scale = 4)
    private BigDecimal annualRatePct;

    @Enumerated(EnumType.STRING)
    @Column(name = "rate_type", nullable = false, length = 30)
    private RateType rateType;

    @Column(name = "tenure_months", nullable = false)
    private Integer tenureMonths;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "first_emi_date", nullable = false)
    private LocalDate firstEmiDate;

    @Column(name = "emi_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal emiAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private LoanStatus status = LoanStatus.active;

    @Column(length = 1000)
    private String notes;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
        if (status == null) {
            status = LoanStatus.active;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }
}
