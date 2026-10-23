package com.financeos.domain.investment;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface InvestmentTransactionRepository extends JpaRepository<InvestmentTransaction, UUID> {

    List<InvestmentTransaction> findByHoldingIdOrderByTradeDateAscCreatedAtAsc(UUID holdingId);

    /** {@link #findByHoldingIdOrderByTradeDateAscCreatedAtAsc} for several holdings at once (callers chunk the ids). */
    List<InvestmentTransaction> findByHoldingIdInOrderByTradeDateAscCreatedAtAsc(java.util.Collection<UUID> holdingIds);

    /** Every investment transaction of one user, in the engine's order (batch input for the lot engine). */
    List<InvestmentTransaction> findByUser_IdOrderByTradeDateAscCreatedAtAsc(UUID userId);

    /**
     * The trade's owner renamed the instrument (or its symbol) to something containing the search.
     * Searches are plain substrings (LOCATE, not LIKE: a typed % or _ is not a wildcard).
     */
    String OWN_NAME_MATCHES = "EXISTS (SELECT 1 FROM UserInstrumentOverride o WHERE o.instrumentId = i.id "
            + "AND o.userId = t.user.id AND (LOCATE(LOWER(:search), LOWER(o.name)) > 0 "
            + "OR LOCATE(LOWER(:search), LOWER(o.symbol)) > 0))";

    @Query(value = "SELECT t FROM InvestmentTransaction t JOIN FETCH t.holding h JOIN FETCH h.instrument i JOIN FETCH h.brokerAccount b LEFT JOIN FETCH b.brokerDetails bd WHERE " +
                   "(:brokerAccountId IS NULL OR b.id = :brokerAccountId) AND " +
                   "(:instrumentId IS NULL OR i.id = :instrumentId) AND " +
                   "(:holdingId IS NULL OR h.id = :holdingId) AND " +
                   "(:search IS NULL OR " +
                   "LOCATE(LOWER(:search), LOWER(i.name)) > 0 OR " +
                   "LOCATE(LOWER(:search), LOWER(i.symbol)) > 0 OR " +
                   "LOCATE(LOWER(:search), LOWER(i.yahooSymbol)) > 0 OR " + OWN_NAME_MATCHES + ")",
           countQuery = "SELECT COUNT(t) FROM InvestmentTransaction t JOIN t.holding h JOIN h.instrument i JOIN h.brokerAccount b WHERE " +
                        "(:brokerAccountId IS NULL OR b.id = :brokerAccountId) AND " +
                        "(:instrumentId IS NULL OR i.id = :instrumentId) AND " +
                        "(:holdingId IS NULL OR h.id = :holdingId) AND " +
                        "(:search IS NULL OR " +
                        "LOCATE(LOWER(:search), LOWER(i.name)) > 0 OR " +
                        "LOCATE(LOWER(:search), LOWER(i.symbol)) > 0 OR " +
                        "LOCATE(LOWER(:search), LOWER(i.yahooSymbol)) > 0 OR " + OWN_NAME_MATCHES + ")")
    Page<InvestmentTransaction> findFilteredTransactions(
            @Param("brokerAccountId") UUID brokerAccountId,
            @Param("instrumentId") UUID instrumentId,
            @Param("holdingId") UUID holdingId,
            @Param("search") String search,
            Pageable pageable);

    /**
     * {@link #findFilteredTransactions} also narrowed by buy/sell and a trade-date window (inclusive);
     * a null type matches both. Callers pass real dates for the window (no null date parameters).
     */
    @Query(value = "SELECT t FROM InvestmentTransaction t JOIN FETCH t.holding h JOIN FETCH h.instrument i JOIN FETCH h.brokerAccount b LEFT JOIN FETCH b.brokerDetails bd WHERE " +
                   "(:brokerAccountId IS NULL OR b.id = :brokerAccountId) AND " +
                   "(:instrumentId IS NULL OR i.id = :instrumentId) AND " +
                   "(:holdingId IS NULL OR h.id = :holdingId) AND " +
                   "(:type IS NULL OR t.type = :type) AND " +
                   "t.tradeDate >= :fromDate AND t.tradeDate <= :toDate AND " +
                   "(:search IS NULL OR " +
                   "LOCATE(LOWER(:search), LOWER(i.name)) > 0 OR " +
                   "LOCATE(LOWER(:search), LOWER(i.symbol)) > 0 OR " +
                   "LOCATE(LOWER(:search), LOWER(i.yahooSymbol)) > 0 OR " + OWN_NAME_MATCHES + ")",
           countQuery = "SELECT COUNT(t) FROM InvestmentTransaction t JOIN t.holding h JOIN h.instrument i JOIN h.brokerAccount b WHERE " +
                        "(:brokerAccountId IS NULL OR b.id = :brokerAccountId) AND " +
                        "(:instrumentId IS NULL OR i.id = :instrumentId) AND " +
                        "(:holdingId IS NULL OR h.id = :holdingId) AND " +
                        "(:type IS NULL OR t.type = :type) AND " +
                        "t.tradeDate >= :fromDate AND t.tradeDate <= :toDate AND " +
                        "(:search IS NULL OR " +
                        "LOCATE(LOWER(:search), LOWER(i.name)) > 0 OR " +
                        "LOCATE(LOWER(:search), LOWER(i.symbol)) > 0 OR " +
                        "LOCATE(LOWER(:search), LOWER(i.yahooSymbol)) > 0 OR " + OWN_NAME_MATCHES + ")")
    Page<InvestmentTransaction> findFilteredTransactionsBetween(
            @Param("brokerAccountId") UUID brokerAccountId,
            @Param("instrumentId") UUID instrumentId,
            @Param("holdingId") UUID holdingId,
            @Param("type") InvestmentTransactionType type,
            @Param("fromDate") java.time.LocalDate fromDate,
            @Param("toDate") java.time.LocalDate toDate,
            @Param("search") String search,
            Pageable pageable);

    /** The earliest buy of the instrument across the user's holdings; null with none. */
    @Query("SELECT MIN(t.tradeDate) FROM InvestmentTransaction t WHERE t.user.id = :userId AND t.holding.instrument.id = :instrumentId "
            + "AND t.type = com.financeos.domain.investment.InvestmentTransactionType.buy")
    java.time.LocalDate findFirstBuyDate(@Param("userId") UUID userId, @Param("instrumentId") UUID instrumentId);
}
