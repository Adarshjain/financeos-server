package com.financeos.domain.investment;

import com.financeos.domain.holding.Holding;
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
    @JoinColumn(name = "holding_id", nullable = false)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    private Holding holding;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private InvestmentTransactionType type;

    @Column(nullable = false, precision = 19, scale = 8)
    private BigDecimal quantity;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal price;

    @Column(name = "trade_date", nullable = false)
    private LocalDate tradeDate;

    @Column(precision = 19, scale = 4)
    private BigDecimal brokerage;

    @Column(precision = 19, scale = 4)
    private BigDecimal stt;

    @Column(name = "exchange_txn_charges", precision = 19, scale = 4)
    private BigDecimal exchangeTxnCharges;

    @Column(name = "sebi_charges", precision = 19, scale = 4)
    private BigDecimal sebiCharges;

    @Column(name = "stamp_duty", precision = 19, scale = 4)
    private BigDecimal stampDuty;

    @Column(precision = 19, scale = 4)
    private BigDecimal gst;

    @Column(name = "dp_charges", precision = 19, scale = 4)
    private BigDecimal dpCharges;

    @Column(name = "other_charges", precision = 19, scale = 4)
    private BigDecimal otherCharges;

    @Column(name = "total_charges", precision = 19, scale = 4)
    private BigDecimal totalCharges = BigDecimal.ZERO;

    @Column
    private String source = "manual";

    @Column(name = "external_ref")
    private String externalRef;

    @Column
    private String notes;

    @Column(name = "created_at")
    private Instant createdAt;

    @PrePersist
    @PreUpdate
    protected void calculateTotalCharges() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        BigDecimal sum = BigDecimal.ZERO;
        if (brokerage != null) sum = sum.add(brokerage);
        if (stt != null) sum = sum.add(stt);
        if (exchangeTxnCharges != null) sum = sum.add(exchangeTxnCharges);
        if (sebiCharges != null) sum = sum.add(sebiCharges);
        if (stampDuty != null) sum = sum.add(stampDuty);
        if (gst != null) sum = sum.add(gst);
        if (dpCharges != null) sum = sum.add(dpCharges);
        if (otherCharges != null) sum = sum.add(otherCharges);
        this.totalCharges = sum;
    }
}
