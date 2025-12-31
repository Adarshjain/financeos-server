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

    @Query("SELECT it FROM InvestmentTransaction it WHERE it.account.id = :accountId ORDER BY it.date ASC, it.createdAt ASC")
    List<InvestmentTransaction> findByAccountIdOrderByDateAsc(@Param("accountId") UUID accountId);

    Page<InvestmentTransaction> findByAccountId(UUID accountId, Pageable pageable);

    @Query("SELECT it FROM InvestmentTransaction it ORDER BY it.date DESC, it.createdAt DESC")
    Page<InvestmentTransaction> findAllOrdered(Pageable pageable);

    @Query("SELECT DISTINCT it.account.id FROM InvestmentTransaction it")
    List<UUID> findDistinctAccountIds();
}

