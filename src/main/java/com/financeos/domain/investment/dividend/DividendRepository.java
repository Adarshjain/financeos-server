package com.financeos.domain.investment.dividend;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Repository
public interface DividendRepository extends JpaRepository<Dividend, UUID> {

    List<Dividend> findByHoldingIdOrderByPayDateDescCreatedAtDesc(UUID holdingId);

    List<Dividend> findByHoldingBrokerAccountIdOrderByPayDateDescCreatedAtDesc(UUID brokerAccountId);

    @Query(value = "SELECT d FROM Dividend d JOIN FETCH d.holding h JOIN FETCH h.instrument i JOIN FETCH h.brokerAccount b LEFT JOIN FETCH b.brokerDetails bd WHERE " +
                   "(:holdingId IS NULL OR h.id = :holdingId) AND " +
                   "(:brokerAccountId IS NULL OR b.id = :brokerAccountId) AND " +
                   "(:instrumentId IS NULL OR i.id = :instrumentId) AND " +
                   "(:type IS NULL OR d.type = :type) AND " +
                   "(:fromDate IS NULL OR d.payDate >= :fromDate) AND " +
                   "(:toDate IS NULL OR d.payDate <= :toDate)",
           countQuery = "SELECT COUNT(d) FROM Dividend d JOIN d.holding h JOIN h.instrument i JOIN h.brokerAccount b WHERE " +
                        "(:holdingId IS NULL OR h.id = :holdingId) AND " +
                        "(:brokerAccountId IS NULL OR b.id = :brokerAccountId) AND " +
                        "(:instrumentId IS NULL OR i.id = :instrumentId) AND " +
                        "(:type IS NULL OR d.type = :type) AND " +
                        "(:fromDate IS NULL OR d.payDate >= :fromDate) AND " +
                        "(:toDate IS NULL OR d.payDate <= :toDate)")
    Page<Dividend> findFilteredDividends(
            @Param("holdingId") UUID holdingId,
            @Param("brokerAccountId") UUID brokerAccountId,
            @Param("instrumentId") UUID instrumentId,
            @Param("type") DividendType type,
            @Param("fromDate") LocalDate fromDate,
            @Param("toDate") LocalDate toDate,
            Pageable pageable);

    @Query("SELECT d.payDate, d.amount, d.tds FROM Dividend d JOIN d.holding h JOIN h.instrument i JOIN h.brokerAccount b WHERE " +
           "(:holdingId IS NULL OR h.id = :holdingId) AND " +
           "(:brokerAccountId IS NULL OR b.id = :brokerAccountId) AND " +
           "(:instrumentId IS NULL OR i.id = :instrumentId) AND " +
           "(:type IS NULL OR d.type = :type)")
    List<Object[]> findDividendRowsForSummary(
            @Param("holdingId") UUID holdingId,
            @Param("brokerAccountId") UUID brokerAccountId,
            @Param("instrumentId") UUID instrumentId,
            @Param("type") DividendType type);

    @Query("SELECT SUM(d.amount) FROM Dividend d WHERE d.holding.id = :holdingId")
    BigDecimal sumAmountByHoldingId(@Param("holdingId") UUID holdingId);

    @Query("SELECT SUM(d.amount) FROM Dividend d")
    BigDecimal sumTotalUserDividends();
}
