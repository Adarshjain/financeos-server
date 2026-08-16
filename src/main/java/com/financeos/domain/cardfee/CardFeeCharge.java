package com.financeos.domain.cardfee;

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
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "card_fee_charges")
@Getter
@Setter
@NoArgsConstructor
@Filter(name = "userFilter", condition = "user_id = :userId")
public class CardFeeCharge {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(length = 36)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id", nullable = false)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    private Account account;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CardFeeKind kind;

    @Column(name = "fee_year_start", nullable = false)
    private LocalDate feeYearStart;

    /** Tri-state: null = auto-evaluate, true = manually waived, false = manually charged. */
    @Column
    private Boolean waived;

    @Column(name = "override_amount", precision = 19, scale = 4)
    private BigDecimal overrideAmount;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "card_fee_charge_transactions", joinColumns = @JoinColumn(name = "charge_id"))
    @Column(name = "transaction_id")
    @JdbcTypeCode(SqlTypes.VARCHAR)
    private Set<UUID> transactionIds = new HashSet<>();

    @Column(length = 500)
    private String note;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
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
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }
}
