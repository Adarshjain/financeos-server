package com.financeos.domain.reward;

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

/**
 * A milestone benefit on one account: reach a spend or transaction-count threshold
 * within a window → fixed cash payout or progress tracking. Milestone-eligible
 * spend is configured INDEPENDENTLY from earn rules (banks use different lists);
 * the include/exclude lists live in {@link #eligibility} as JSON text.
 */
@Entity
@Table(name = "reward_milestones")
@Getter
@Setter
@NoArgsConstructor
@Filter(name = "userFilter", condition = "user_id = :userId")
public class RewardMilestone {

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

    @Column(nullable = false, length = 200)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "window_type", nullable = false, length = 20)
    private MilestoneWindow windowType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MilestoneBasis basis;

    /** Rupees for SPEND, transaction count for TXN_COUNT. */
    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal threshold;

    /** TXN_COUNT only: a transaction must be at least this large to count (Amex-style). */
    @Column(name = "min_txn_amount", precision = 19, scale = 4)
    private BigDecimal minTxnAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "payout_type", nullable = false, length = 20)
    private MilestonePayoutType payoutType;

    /** Currency of a CASH_VALUE payout: cashback rupees or reward points. */
    @Enumerated(EnumType.STRING)
    @Column(name = "reward_type", nullable = false, length = 20)
    private RewardType rewardType = RewardType.CASH;

    /** Value granted per achieved window (CASH_VALUE only), in the reward type's unit. */
    @Column(name = "payout_value", precision = 19, scale = 4)
    private BigDecimal payoutValue;

    /** When the payout lands in the report: window end vs the threshold-crossing date. */
    @Enumerated(EnumType.STRING)
    @Column(name = "payout_timing", nullable = false, length = 20)
    private MilestonePayoutTiming payoutTiming = MilestonePayoutTiming.WINDOW_END;

    /** JSON: {includeCategoryIds, includeMccs, excludeCategoryIds, excludeMccs}. Null = all eligible spend counts. */
    @JdbcTypeCode(SqlTypes.CLOB)
    @Column(name = "eligibility")
    private String eligibility;

    @Column(name = "active_from")
    private LocalDate activeFrom;

    @Column(name = "active_to")
    private LocalDate activeTo;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

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

    public boolean isActiveOn(LocalDate date) {
        return (activeFrom == null || !date.isBefore(activeFrom))
                && (activeTo == null || date.isBefore(activeTo));
    }
}
