package com.financeos.domain.transaction;

import com.financeos.domain.account.Account;
import com.financeos.domain.category.Category;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.hibernate.annotations.Filter;
import com.financeos.domain.user.User;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import com.financeos.core.util.UuidGenerator;

@Entity
@Table(name = "transactions")
@Getter
@Setter
@NoArgsConstructor
@Filter(name = "userFilter", condition = "user_id = :userId")
public class Transaction {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id")
    private Account account;

    @Column(name = "transaction_date", nullable = false)
    private LocalDate date;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(nullable = false)
    private String description;

    @ManyToMany(fetch = FetchType.LAZY, cascade = { CascadeType.PERSIST, CascadeType.MERGE })
    @JoinTable(name = "transaction_categories", joinColumns = @JoinColumn(name = "transaction_id"), inverseJoinColumns = @JoinColumn(name = "category_id"))
    private Set<Category> categories = new HashSet<>();

    @Column(name = "is_transaction_excluded", nullable = false)
    private Boolean isTransactionExcluded = false;

    @Column(name = "is_transaction_under_monitoring", nullable = false)
    private Boolean isTransactionUnderMonitoring = false;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TransactionSource source;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TransactionType type;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata")
    private Map<String, Object> metadata;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (id == null) {
            id = UuidGenerator.generateUuid7();
        }
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    public Transaction(Account account, LocalDate date, BigDecimal amount, String description,
            TransactionSource source, TransactionType type) {
        this.account = account;
        this.date = date;
        this.amount = amount;
        this.description = description;
        this.source = source;
        this.type = type;
    }
}
