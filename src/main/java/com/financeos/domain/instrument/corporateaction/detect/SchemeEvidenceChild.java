package com.financeos.domain.instrument.corporateaction.detect;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** One child company (demerger) or the acquirer (merger) of a {@link SchemeEvidence}, with its ratio and cost share. */
@Entity
@Table(name = "corporate_scheme_children")
@Getter
@Setter
@NoArgsConstructor
public class SchemeEvidenceChild {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(length = 36)
    private UUID id;

    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "evidence_id", nullable = false, length = 36)
    private UUID evidenceId;

    @Column(name = "child_order", nullable = false)
    private int childOrder;

    @Column(name = "child_symbol", nullable = false, length = 40)
    private String childSymbol;

    @Column(name = "child_isin", length = 20)
    private String childIsin;

    @Column(name = "child_name", nullable = false, length = 200)
    private String childName;

    @Column(name = "listed_on")
    private LocalDate listedOn;

    @Column(name = "ratio_from")
    private Integer ratioFrom;

    @Column(name = "ratio_to")
    private Integer ratioTo;

    @Column(name = "ratio_url", length = 500)
    private String ratioUrl;

    @Column(name = "ratio_notice_date")
    private LocalDate ratioNoticeDate;

    @Column(name = "ratio_quote", length = 300)
    private String ratioQuote;

    @Column(name = "child_cost_pct", precision = 9, scale = 4)
    private BigDecimal childCostPct;
}
