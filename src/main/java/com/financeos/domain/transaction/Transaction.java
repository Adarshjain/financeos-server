package com.financeos.domain.transaction;

import com.financeos.domain.account.Account;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.hibernate.annotations.Filter;
import com.financeos.domain.user.User;
import com.financeos.domain.category.Category;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;
import java.util.HashSet;
import java.util.UUID;
import com.financeos.domain.categorization.CategoryRule;

@Entity
@Table(name = "transactions")
@Getter
@Setter
@NoArgsConstructor
@Filter(name = "userFilter", condition = "user_id = :userId")
public class Transaction {

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

    @Column(name = "transaction_date", nullable = false)
    private LocalDate date;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(nullable = true)
    private String description;

    @Column(name = "sourced_description", length = 4000)
    private String sourcedDescription;

    @Column(name = "mcc", length = 4)
    private String mcc;

    @Transient
    private BigDecimal balance;

    @OneToMany(mappedBy = "transaction", cascade = CascadeType.ALL, orphanRemoval = true)
    private Set<TransactionCategory> categories = new HashSet<>();

    public void setCategories(Set<Category> newCategories) {
        if (newCategories == null) {
            this.categories.clear();
            return;
        }

        // 1. Remove categories that are no longer present
        this.categories.removeIf(tc -> !newCategories.contains(tc.getCategory()));

        // 2. Identify categories already present to avoid redundant inserts
        java.util.Set<Category> existingCategories = this.categories.stream()
                .map(TransactionCategory::getCategory)
                .collect(java.util.stream.Collectors.toSet());

        // 3. Add only the newly associated categories
        for (Category category : newCategories) {
            if (!existingCategories.contains(category)) {
                this.categories.add(new TransactionCategory(this, category));
            }
        }
    }

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TransactionSource source;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TransactionType type;

    @Column(name = "is_under_monitoring", nullable = false)
    private boolean isTransactionUnderMonitoring = false;

    @Column(name = "monitoring_reason", length = 500)
    private String monitoringReason;

    @Column(name = "is_excluded", nullable = false)
    private boolean isTransactionExcluded = false;

    @Enumerated(EnumType.STRING)
    @Column(name = "review_type")
    private ReviewType reviewType;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "applied_rule_id")
    private CategoryRule appliedRule;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "transaction_review_reasons", joinColumns = @JoinColumn(name = "transaction_id"))
    @Column(name = "reason")
    @Enumerated(EnumType.STRING)
    @org.hibernate.annotations.BatchSize(size = 50)
    private Set<ReviewReason> reviewReasons = new HashSet<>();

    @Column(name = "source_message_id", unique = true)
    private String sourceMessageId;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        if (updatedAt == null) {
            updatedAt = Instant.now();
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }

    public Transaction(Account account, LocalDate date, BigDecimal amount, String description,
            TransactionSource source, TransactionType type,
            boolean isTransactionUnderMonitoring, boolean isTransactionExcluded) {
        this.account = account;
        this.date = date;
        this.amount = amount;
        this.description = description;
        this.source = source;
        this.type = type;
        this.isTransactionUnderMonitoring = isTransactionUnderMonitoring;
        this.isTransactionExcluded = isTransactionExcluded;
    }
}
