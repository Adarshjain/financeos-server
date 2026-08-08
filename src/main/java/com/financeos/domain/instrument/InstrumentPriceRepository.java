package com.financeos.domain.instrument;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface InstrumentPriceRepository extends JpaRepository<InstrumentPrice, UUID> {

    Optional<InstrumentPrice> findTopByInstrumentIdOrderByAsOfDesc(UUID instrumentId);

    Optional<InstrumentPrice> findByInstrumentIdAndAsOf(UUID instrumentId, LocalDate asOf);

    @Query("SELECT p FROM InstrumentPrice p WHERE p.instrument.id = :instrumentId AND " +
           "(:from IS NULL OR p.asOf >= :from) AND " +
           "(:to IS NULL OR p.asOf <= :to) ORDER BY p.asOf DESC")
    List<InstrumentPrice> findPriceHistory(
            @Param("instrumentId") UUID instrumentId,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to);

    List<InstrumentPrice> findByInstrumentIdInOrderByAsOfAsc(List<UUID> instrumentIds);
}
