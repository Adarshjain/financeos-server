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

    @Query(value = "SELECT t FROM InvestmentTransaction t JOIN FETCH t.holding h JOIN FETCH h.instrument i JOIN FETCH h.brokerAccount b WHERE " +
                   "(:brokerAccountId IS NULL OR b.id = :brokerAccountId) AND " +
                   "(:instrumentId IS NULL OR i.id = :instrumentId) AND " +
                   "(:holdingId IS NULL OR h.id = :holdingId)",
           countQuery = "SELECT COUNT(t) FROM InvestmentTransaction t JOIN t.holding h JOIN h.instrument i JOIN h.brokerAccount b WHERE " +
                        "(:brokerAccountId IS NULL OR b.id = :brokerAccountId) AND " +
                        "(:instrumentId IS NULL OR i.id = :instrumentId) AND " +
                        "(:holdingId IS NULL OR h.id = :holdingId)")
    Page<InvestmentTransaction> findFilteredTransactions(
            @Param("brokerAccountId") UUID brokerAccountId,
            @Param("instrumentId") UUID instrumentId,
            @Param("holdingId") UUID holdingId,
            Pageable pageable);
}
