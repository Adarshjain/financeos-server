package com.financeos.domain.instrument.corporateaction.detect;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** One structural corporate event read from the exchange's feed. Global market data, not per user. */
@Entity
@Table(name = "market_corporate_events")
@Getter
@Setter
@NoArgsConstructor
public class MarketCorporateEvent {

    public static final String SOURCE_NSE = "nse";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(length = 36)
    private UUID id;

    @Column(nullable = false, length = 10)
    private String source;

    @Column(nullable = false, length = 40)
    private String symbol;

    @Column(length = 20)
    private String isin;

    @Column(length = 200)
    private String company;

    @Column(length = 10)
    private String series;

    @Column(name = "ex_date", nullable = false)
    private LocalDate exDate;

    @Column(nullable = false, length = 500)
    private String subject;

    /** split, bonus, demerger, merger or other. */
    @Column(name = "parsed_type", length = 20)
    private String parsedType;

    @Column(name = "ratio_from")
    private Integer ratioFrom;

    @Column(name = "ratio_to")
    private Integer ratioTo;

    /** True only for exactly one bonus or one split (plus ignorable parts): safe to apply once a second source agrees. */
    @Column(nullable = false)
    private boolean clean;

    @Column(name = "fetched_at")
    private Instant fetchedAt;
}
