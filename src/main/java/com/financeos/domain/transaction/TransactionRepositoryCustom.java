package com.financeos.domain.transaction;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

/**
 * Fragment interface for dynamic native-SQL queries on transactions.
 */
public interface TransactionRepositoryCustom {

    /** An account's credits and debits dated after its cutoff (zero when it has none). */
    record SumsAfter(java.math.BigDecimal credits, java.math.BigDecimal debits) {
    }

    /**
     * {@code sumCreditsAfter} and {@code sumDebitsAfter} for several accounts at once, each over its own
     * cutoff (strictly after). Every account asked for has an entry.
     */
    Map<UUID, SumsAfter> sumsAfterCutoffs(Map<UUID, LocalDate> cutoffByAccountId);

    /**
     * Executes the filtered transaction query computing true running balances.
     */
    Page<TransactionRepository.TransactionBalanceProjection> findFiltered(
            UUID userId,
            TransactionSearchCriteria criteria,
            Pageable pageable
    );
}
