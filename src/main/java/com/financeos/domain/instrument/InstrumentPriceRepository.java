package com.financeos.domain.instrument;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface InstrumentPriceRepository extends JpaRepository<InstrumentPrice, UUID> {

    Optional<InstrumentPrice> findTopByInstrumentIdOrderByAsOfDesc(UUID instrumentId);

    Optional<InstrumentPrice> findByInstrumentIdAndAsOf(UUID instrumentId, LocalDate asOf);
}
