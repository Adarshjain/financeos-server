package com.financeos.domain.instrument.corporateaction.detect;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Repository
public interface MarketCorporateEventRepository extends JpaRepository<MarketCorporateEvent, UUID> {

    /** One source's events with an ex-date in [from, to] (the existing keys of a window about to be refreshed). */
    @Query("SELECT e FROM MarketCorporateEvent e WHERE e.source = :source AND e.exDate BETWEEN :from AND :to")
    List<MarketCorporateEvent> findWindow(@Param("source") String source, @Param("from") LocalDate from, @Param("to") LocalDate to);

    /** One source's events with an ex-date on or after {@code from}, oldest first. */
    @Query("SELECT e FROM MarketCorporateEvent e WHERE e.source = :source AND e.exDate >= :from ORDER BY e.exDate ASC, e.symbol ASC")
    List<MarketCorporateEvent> findFrom(@Param("source") String source, @Param("from") LocalDate from);
}
