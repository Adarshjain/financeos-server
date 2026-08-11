package com.financeos.domain.reward;

import com.financeos.domain.account.Account;
import com.financeos.domain.category.Category;
import com.financeos.domain.transaction.TransactionChannel;
import com.financeos.domain.user.User;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * A user-configured reward rule for one account. Effective-dated: devaluations are
 * modeled by end-dating a rule and creating its successor, never by editing history.
 * Match predicates combine as (categories OR mccs) AND merchant AND channel AND
 * day-of-week AND amount band AND EMI/intl treatment AND active date range.
 */
@Entity
@Table(name = "reward_rules")
@Getter
@Setter
@NoArgsConstructor
@Filter(name = "userFilter", condition = "user_id = :userId")
public class RewardRule {

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

    /** Higher wins among EXCLUSIVE rules. */
    @Column(nullable = false)
    private int priority;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RuleStacking stacking = RuleStacking.EXCLUSIVE;

    /** Inclusive start; null = active since forever. */
    @Column(name = "active_from")
    private LocalDate activeFrom;

    /** Exclusive end; null = open-ended. */
    @Column(name = "active_to")
    private LocalDate activeTo;

    // ---- match predicates (all optional; unset = matches everything) ----

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
            name = "reward_rule_categories",
            joinColumns = @JoinColumn(name = "rule_id"),
            inverseJoinColumns = @JoinColumn(name = "category_id")
    )
    private Set<Category> categories = new HashSet<>();

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "reward_rule_mccs", joinColumns = @JoinColumn(name = "rule_id"))
    @Column(name = "mcc", length = 4)
    @org.hibernate.annotations.BatchSize(size = 50)
    private Set<String> mccs = new HashSet<>();

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "reward_rule_channels", joinColumns = @JoinColumn(name = "rule_id"))
    @Column(name = "channel", length = 20)
    @Enumerated(EnumType.STRING)
    @org.hibernate.annotations.BatchSize(size = 50)
    private Set<TransactionChannel> channels = new HashSet<>();

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "reward_rule_days", joinColumns = @JoinColumn(name = "rule_id"))
    @Column(name = "day_of_week", length = 10)
    @Enumerated(EnumType.STRING)
    @org.hibernate.annotations.BatchSize(size = 50)
    private Set<DayOfWeek> daysOfWeek = new HashSet<>();

    @Column(name = "merchant_pattern", length = 500)
    private String merchantPattern;

    @Enumerated(EnumType.STRING)
    @Column(name = "merchant_match", length = 20)
    private RewardMerchantMatch merchantMatch;

    @Column(name = "min_amount", precision = 19, scale = 4)
    private BigDecimal minAmount;

    @Column(name = "max_amount", precision = 19, scale = 4)
    private BigDecimal maxAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "emi_treatment", nullable = false, length = 20)
    private EmiTreatment emiTreatment = EmiTreatment.INCLUDE;

    @Enumerated(EnumType.STRING)
    @Column(name = "intl_treatment", nullable = false, length = 20)
    private IntlTreatment intlTreatment = IntlTreatment.INCLUDE;

    // ---- accrual ----

    /** Currency the accrued number pays in: cashback rupees or reward points. */
    @Enumerated(EnumType.STRING)
    @Column(name = "reward_type", nullable = false, length = 20)
    private RewardType rewardType = RewardType.CASH;

    @Enumerated(EnumType.STRING)
    @Column(name = "accrual_type", nullable = false, length = 20)
    private AccrualType accrualType;

    @Column(name = "percent_rate", precision = 9, scale = 4)
    private BigDecimal percentRate;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private CashbackRounding rounding;

    @Column(name = "slab_size", precision = 19, scale = 4)
    private BigDecimal slabSize;

    @Column(name = "points_per_slab", precision = 19, scale = 4)
    private BigDecimal pointsPerSlab;

    /** Display decimals for points; null/0 = whole points (floor). */
    @Column(name = "point_precision")
    private Integer pointPrecision;

    // ---- tiered (marginal) rate — optional; replaces the flat rate when set ----

    /** Window the running matched-spend total accumulates over (required when tiers set). */
    @Enumerated(EnumType.STRING)
    @Column(name = "tier_window", length = 20)
    private CapWindow tierWindow;

    /** JSON array of {@link RewardTier}; null = flat rate. */
    @JdbcTypeCode(SqlTypes.CLOB)
    @Column(name = "tiers")
    private String tiers;

    public boolean isTiered() {
        return tiers != null && !tiers.isBlank();
    }

    // ---- caps (unit = the rule's reward type: rupees for CASH, points for POINTS) ----

    @Column(name = "per_txn_cap", precision = 19, scale = 4)
    private BigDecimal perTxnCap;

    @Column(name = "period_cap", precision = 19, scale = 4)
    private BigDecimal periodCap;

    /** When set, this bucket's cap+window replace the rule's own period cap. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cap_bucket_id")
    @JdbcTypeCode(SqlTypes.VARCHAR)
    private RewardCapBucket capBucket;

    public boolean hasPeriodCap() {
        return periodCap != null || capBucket != null;
    }

    @Enumerated(EnumType.STRING)
    @Column(name = "cap_window", length = 20)
    private CapWindow capWindow;

    @Enumerated(EnumType.STRING)
    @Column(name = "on_cap_exhausted", nullable = false, length = 20)
    private CapExhaustedBehavior onCapExhausted = CapExhaustedBehavior.FALL_THROUGH;

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

    /** Whether this rule is active on the given effective date. */
    public boolean isActiveOn(LocalDate date) {
        return (activeFrom == null || !date.isBefore(activeFrom))
                && (activeTo == null || date.isBefore(activeTo));
    }
}
