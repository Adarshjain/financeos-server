package com.financeos.domain.investment.dividend;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface DividendRepository extends JpaRepository<Dividend, UUID> {

    String BASE_FILTERS =
            "(:holdingId IS NULL OR h.id = :holdingId) AND " +
            "(:brokerAccountId IS NULL OR b.id = :brokerAccountId) AND " +
            "(:instrumentId IS NULL OR i.id = :instrumentId) AND " +
            "(:type IS NULL OR d.type = :type)";

    String DATE_FILTERS =
            "(:fromDate IS NULL OR d.payDate >= :fromDate) AND " +
            "(:toDate IS NULL OR d.payDate <= :toDate)";

    String SRC = "COALESCE(d.source, 'manual')";
    String SUGGESTED_BASE = "COALESCE(d.exDate, d.payDate)";
    String UNRESOLVED = "d.transaction IS NULL AND d.receiptStatus IS NULL";

    /**
     * Receipt-status filter. The derived statuses (awaiting / overdue / unverifiable) compare each
     * row's base date against per-source cut-offs computed by
     * {@link DividendReceiptWindows#thresholds}; see that method for the algebra.
     */
    String RECEIPT_PREDICATE =
            " AND (:receipt IS NULL" +
            " OR (:receipt = 'received' AND d.transaction IS NOT NULL)" +
            " OR (:receipt = 'received_untracked' AND d.receiptStatus = com.financeos.domain.investment.dividend.DividendReceiptStatus.received_untracked)" +
            " OR (:receipt = 'not_received' AND d.receiptStatus = com.financeos.domain.investment.dividend.DividendReceiptStatus.not_received)" +
            " OR (:receipt = 'awaiting' AND " + UNRESOLVED + " AND (" +
            "      (" + SRC + " = 'suggested' AND " + SUGGESTED_BASE + " >= :sugAwaitFrom)" +
            "   OR (" + SRC + " = 'import' AND d.payDate >= :impAwaitFrom)" +
            "   OR (" + SRC + " NOT IN ('suggested', 'import') AND d.payDate >= :manAwaitFrom)))" +
            " OR (:receipt = 'overdue' AND " + UNRESOLVED + " AND (" +
            "      (" + SRC + " = 'suggested' AND " + SUGGESTED_BASE + " < :sugOverdueBefore)" +
            "   OR (" + SRC + " = 'import' AND d.payDate < :impOverdueBefore)" +
            "   OR (" + SRC + " NOT IN ('suggested', 'import') AND d.payDate < :manOverdueBefore)))" +
            " OR (:receipt = 'unverifiable' AND " + UNRESOLVED + " AND (" +
            "      (" + SRC + " = 'suggested' AND " + SUGGESTED_BASE + " < :sugAwaitFrom AND " + SUGGESTED_BASE + " >= :sugOverdueBefore)" +
            "   OR (" + SRC + " = 'import' AND d.payDate < :impAwaitFrom AND d.payDate >= :impOverdueBefore)" +
            "   OR (" + SRC + " NOT IN ('suggested', 'import') AND d.payDate < :manAwaitFrom AND d.payDate >= :manOverdueBefore))))";

    List<Dividend> findByHoldingIdOrderByPayDateDescCreatedAtDesc(UUID holdingId);

    List<Dividend> findByHoldingBrokerAccountIdOrderByPayDateDescCreatedAtDesc(UUID brokerAccountId);

    boolean existsByTransaction_Id(UUID transactionId);

    @Query(value = "SELECT d FROM Dividend d JOIN FETCH d.holding h JOIN FETCH h.instrument i JOIN FETCH h.brokerAccount b LEFT JOIN FETCH b.brokerDetails bd " +
                   "LEFT JOIN FETCH d.transaction t LEFT JOIN FETCH t.account ta WHERE " +
                   BASE_FILTERS + " AND " + DATE_FILTERS + RECEIPT_PREDICATE,
           countQuery = "SELECT COUNT(d) FROM Dividend d JOIN d.holding h JOIN h.instrument i JOIN h.brokerAccount b WHERE " +
                        BASE_FILTERS + " AND " + DATE_FILTERS + RECEIPT_PREDICATE)
    Page<Dividend> findFilteredDividends(
            @Param("holdingId") UUID holdingId,
            @Param("brokerAccountId") UUID brokerAccountId,
            @Param("instrumentId") UUID instrumentId,
            @Param("type") DividendType type,
            @Param("fromDate") LocalDate fromDate,
            @Param("toDate") LocalDate toDate,
            @Param("receipt") String receipt,
            @Param("sugAwaitFrom") LocalDate sugAwaitFrom,
            @Param("impAwaitFrom") LocalDate impAwaitFrom,
            @Param("manAwaitFrom") LocalDate manAwaitFrom,
            @Param("sugOverdueBefore") LocalDate sugOverdueBefore,
            @Param("impOverdueBefore") LocalDate impOverdueBefore,
            @Param("manOverdueBefore") LocalDate manOverdueBefore,
            Pageable pageable);

    @Query("SELECT d.payDate, d.amount, d.tds FROM Dividend d JOIN d.holding h JOIN h.instrument i JOIN h.brokerAccount b WHERE " + BASE_FILTERS)
    List<Object[]> findDividendRowsForSummary(
            @Param("holdingId") UUID holdingId,
            @Param("brokerAccountId") UUID brokerAccountId,
            @Param("instrumentId") UUID instrumentId,
            @Param("type") DividendType type);

    /** Columns the receipt summary needs to derive a status per row without loading entities. */
    @Query("SELECT d.source, d.exDate, d.payDate, d.receiptStatus, d.amount, d.tds, t.id, t.amount " +
           "FROM Dividend d LEFT JOIN d.transaction t JOIN d.holding h JOIN h.instrument i JOIN h.brokerAccount b WHERE " + BASE_FILTERS)
    List<Object[]> findReceiptRowsForSummary(
            @Param("holdingId") UUID holdingId,
            @Param("brokerAccountId") UUID brokerAccountId,
            @Param("instrumentId") UUID instrumentId,
            @Param("type") DividendType type);

    /** Rows with no linked credit and no manual override — the reconciliation input. */
    @Query("SELECT d FROM Dividend d JOIN FETCH d.holding h JOIN FETCH h.instrument i JOIN FETCH h.brokerAccount b WHERE " +
           UNRESOLVED + " AND (:brokerAccountId IS NULL OR b.id = :brokerAccountId) AND " + DATE_FILTERS +
           " ORDER BY d.payDate DESC, d.createdAt DESC")
    List<Dividend> findUnresolvedForReconciliation(
            @Param("brokerAccountId") UUID brokerAccountId,
            @Param("fromDate") LocalDate fromDate,
            @Param("toDate") LocalDate toDate);

    /** Reverse lookup for transaction responses (batch, one query). */
    @Query("SELECT d FROM Dividend d JOIN FETCH d.holding h JOIN FETCH h.instrument i WHERE d.transaction.id IN :ids")
    List<Dividend> findWithHoldingByTransactionIdIn(@Param("ids") Collection<UUID> ids);

    @Query("SELECT SUM(d.amount) FROM Dividend d WHERE d.holding.id = :holdingId")
    BigDecimal sumAmountByHoldingId(@Param("holdingId") UUID holdingId);

    @Query("SELECT SUM(d.amount) FROM Dividend d")
    BigDecimal sumTotalUserDividends();
}
