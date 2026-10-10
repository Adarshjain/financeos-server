package com.financeos.domain.holding;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface HoldingRepository extends JpaRepository<Holding, UUID> {

    /** Whether the user has at least one row. */
    boolean existsByUser_Id(UUID userId);


    Optional<Holding> findByBrokerAccountIdAndInstrumentId(UUID brokerAccountId, UUID instrumentId);

    List<Holding> findByBrokerAccountId(UUID brokerAccountId);

    /**
     * {@code userId}'s holdings of one instrument (one per broker account). A null user matches none:
     * an explicit JPQL equality (a derived query would turn a null owner into {@code IS NULL} and
     * return the owner-less rows).
     */
    @Query("SELECT h FROM Holding h WHERE h.user.id = :userId AND h.instrument.id = :instrumentId")
    List<Holding> findByUser_IdAndInstrument_Id(@Param("userId") UUID userId, @Param("instrumentId") UUID instrumentId);

    /** Every holding {@code userId} owns, broker and instrument fetched (no request filter needed); null matches none. */
    @Query("SELECT DISTINCT h FROM Holding h LEFT JOIN FETCH h.brokerAccount b LEFT JOIN FETCH b.brokerDetails "
            + "LEFT JOIN FETCH h.instrument WHERE h.user.id = :userId")
    List<Holding> findAllWithDetailsOfUser(@Param("userId") UUID userId);

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
