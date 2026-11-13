package com.financeos.domain.instrument.corporateaction.detect;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * What the company's exchange notices say about one demerger or merger of a parent stock: the cost split and the
 * bookkeeping of the reading (the children are {@link SchemeEvidenceChild}). Global market data, not per user.
 * A merger has no ex-date: it is keyed by the parent symbol and kind alone.
 */
@Entity
@Table(name = "corporate_scheme_evidence")
@Getter
@Setter
@NoArgsConstructor
public class SchemeEvidence {

    public static final String COST_NOTICE = "NOTICE";
    public static final String COST_ESTIMATE = "ESTIMATE";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(length = 36)
    private UUID id;

    @Column(name = "parent_symbol", nullable = false, length = 40)
    private String parentSymbol;

    @Column(name = "parent_isin", length = 20)
    private String parentIsin;

    /** demerger or merger. */
    @Column(nullable = false, length = 20)
    private String kind;

    @Column(name = "ex_date")
    private LocalDate exDate;

    @Column(name = "record_date")
    private LocalDate recordDate;

    @Column(name = "parent_cost_pct", precision = 9, scale = 4)
    private BigDecimal parentCostPct;

    /** NOTICE or ESTIMATE; null with no percentage. */
    @Column(name = "cost_kind", length = 10)
    private String costKind;

    @Column(name = "cost_url", length = 500)
    private String costUrl;

    @Column(name = "cost_notice_date")
    private LocalDate costNoticeDate;

    @Column(name = "cost_quote", length = 300)
    private String costQuote;

    /** Comma-separated {@link SchemeNoticeReader} reason codes. */
    @Column(length = 300)
    private String missing;

    /** True once nothing more is worth reading for. */
    @Column(nullable = false)
    private boolean complete;

    @Column(name = "checked_at", nullable = false)
    private Instant checkedAt;

    @Column(name = "next_check_at")
    private Instant nextCheckAt;
}
