package com.financeos.domain.investment;

import com.financeos.domain.account.Account;
import io.hypersistence.utils.hibernate.type.json.JsonType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Type;
import org.hibernate.annotations.Filter;
import com.financeos.domain.user.User;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import com.financeos.core.util.UuidGenerator;

@Entity
@Table(name = "investment_transactions")
@Getter
@Setter
@NoArgsConstructor
@Filter(name = "userFilter", condition = "user_id = :userId")
public class InvestmentTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id")
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

    @Type(JsonType.class)
    @Column(columnDefinition = "jsonb")
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

    public InvestmentTransaction(Account account, InvestmentTransactionType type,
            BigDecimal quantity, BigDecimal price, LocalDate date) {
        this.account = account;
        this.type = type;
        this.quantity = quantity;
        this.price = price;
        this.date = date;
    }
}
