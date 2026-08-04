package com.financeos.domain.instrument.corporateaction;

import com.financeos.domain.instrument.Instrument;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "corporate_actions")
@Getter
@Setter
@NoArgsConstructor
public class CorporateAction {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(length = 36)
    private UUID id;

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
