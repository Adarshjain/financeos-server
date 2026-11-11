package com.financeos.domain.instrument.corporateaction.detect;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;

/** The date range of {@link MarketCorporateEvent}s already loaded for one source. */
@Entity
@Table(name = "market_event_coverage")
@Getter
@Setter
@NoArgsConstructor
public class MarketEventCoverage {

    @Id
    @Column(length = 10)
    private String source;

    @Column(name = "covered_from")
    private LocalDate coveredFrom;

    @Column(name = "covered_to")
    private LocalDate coveredTo;

    @Column(name = "updated_at")
    private Instant updatedAt;
}
