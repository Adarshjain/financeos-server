package com.financeos.domain.investment;

import com.financeos.domain.account.Account;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.hibernate.annotations.Filter;
import com.financeos.domain.user.User;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "investment_transactions")
@Getter
@Setter
@NoArgsConstructor
@Filter(name = "userFilter", condition = "user_id = :userId")
public class InvestmentTransaction {

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
    private InvestmentTransactionType type;

    @Column(nullable = false, precision = 19, scale = 8)
    private BigDecimal quantity;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal price;

    @Column(name = "transaction_date", nullable = false)
    private LocalDate date;

    @Column(name = "created_at")
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public InvestmentTransaction(Account account, InvestmentTransactionType type,
            BigDecimal quantity, BigDecimal price, LocalDate date) {
        this.account = account;
        this.type = type;
        this.quantity = quantity;
        this.price = price;
        this.date = date;
    }
}
