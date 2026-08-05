package com.financeos.domain.holding;

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

    List<Holding> findByInstrumentId(UUID instrumentId);

    @Query("SELECT DISTINCT h FROM Holding h LEFT JOIN FETCH h.brokerAccount b LEFT JOIN FETCH b.brokerDetails LEFT JOIN FETCH h.instrument")
    List<Holding> findAllWithDetails();

    /**
     * Instrument ids that are still actively held, i.e. net open quantity (buys - sells) &gt; 0.
     * Aggregates over investment transactions so fully sold-out positions are excluded.
     * The userFilter (when active, i.e. on an HTTP request) scopes both Holding and
     * InvestmentTransaction to the authenticated user; when inactive (scheduled job) it spans all users.
     */
    @Query("SELECT h.instrument.id FROM Holding h, InvestmentTransaction t WHERE t.holding = h " +
           "GROUP BY h.instrument.id " +
           "HAVING SUM(CASE WHEN t.type = com.financeos.domain.investment.InvestmentTransactionType.buy " +
           "THEN t.quantity ELSE -t.quantity END) > 0")
    List<UUID> findDistinctActiveInstrumentIdsHeld();
}
