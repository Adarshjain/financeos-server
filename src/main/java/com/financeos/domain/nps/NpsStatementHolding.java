package com.financeos.domain.nps;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** Units of one scheme a statement printed at its start (OPENING) or end (CLOSING). */
@Entity
@Table(name = "nps_statement_holding")
@Getter
@Setter
@NoArgsConstructor
public class NpsStatementHolding {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(length = 36)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "statement_id", nullable = false)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    private NpsStatement statement;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private NpsHoldingKind kind;

    @Column(name = "scheme_code", nullable = false, length = 12)
    private String schemeCode;

    @Column(nullable = false, precision = 16, scale = 4)
    private BigDecimal units;

    @Column(precision = 12, scale = 4)
    private BigDecimal nav;

    @Column(name = "nav_date")
    private LocalDate navDate;

    @Column(name = "value", precision = 16, scale = 2)
    private BigDecimal value;

    public NpsStatementHolding(NpsHoldingKind kind, String schemeCode, BigDecimal units, BigDecimal nav,
                               LocalDate navDate, BigDecimal value) {
        this.kind = kind;
        this.schemeCode = schemeCode;
        this.units = units;
        this.nav = nav;
        this.navDate = navDate;
        this.value = value;
    }
}
