package com.financeos.domain.transaction;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

/**
 * Fragment interface for dynamic native-SQL queries on transactions.
 */
public interface TransactionRepositoryCustom {

    /**
     * Executes the filtered transaction query computing true running balances.
     */
    Page<TransactionRepository.TransactionBalanceProjection> findFiltered(
            UUID userId,
            TransactionSearchCriteria criteria,
            Pageable pageable
    );
}
