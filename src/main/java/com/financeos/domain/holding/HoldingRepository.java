package com.financeos.domain.holding;

import com.financeos.domain.instrument.Instrument;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface HoldingRepository extends JpaRepository<Holding, UUID> {

    Optional<Holding> findByBrokerAccountIdAndInstrumentId(UUID brokerAccountId, UUID instrumentId);

    List<Holding> findByBrokerAccountId(UUID brokerAccountId);

    @Query("SELECT DISTINCT h.instrument FROM Holding h")
    List<Instrument> findDistinctInstrumentsHeld();
}
