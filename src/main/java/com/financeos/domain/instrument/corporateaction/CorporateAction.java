package com.financeos.domain.instrument.corporateaction;

import com.financeos.domain.instrument.Instrument;
import com.financeos.domain.user.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A split, bonus, demerger or merger as ONE user recorded it for their own holdings. Corporate actions
 * are per user (V100): each user's lot engine, positions, realised lots, XIRR, tax harvest, dividends
 * and reports read only their own, and a change one user makes never moves another user's numbers.
 * Scoped like every user-owned row (the Hibernate {@code userFilter} on a request), and every
 * repository read also names the owner explicitly so jobs without a signed-in user stay scoped.
 */
@Entity
@Table(name = "corporate_actions")
@Getter
@Setter
@NoArgsConstructor
@Filter(name = "userFilter", condition = "user_id = :userId")
public class CorporateAction {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(length = 36)
    private UUID id;

    /** The user whose holdings the action applies to (NOT NULL in the database since V100). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    @JdbcTypeCode(SqlTypes.VARCHAR)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "instrument_id", nullable = false)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    private Instrument instrument;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CorporateActionType type;

    @Column(name = "ratio_from", nullable = false)
    private Integer ratioFrom;

    @Column(name = "ratio_to", nullable = false)
    private Integer ratioTo;

    @Column(name = "ex_date", nullable = false)
    private LocalDate exDate;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "target_instrument_id")
    @JdbcTypeCode(SqlTypes.VARCHAR)
    private Instrument targetInstrument;

    @Column(name = "cost_allocation_pct", precision = 7, scale = 4)
    private java.math.BigDecimal costAllocationPct;

    @Column(name = "fractional_cash_in_lieu", precision = 19, scale = 4)
    private java.math.BigDecimal fractionalCashInLieu;

    @Column
    private String notes;

    @Column(name = "created_at")
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
