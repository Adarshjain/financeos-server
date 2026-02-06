package com.financeos.domain.transaction;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Repository
public interface TransactionRepository extends JpaRepository<Transaction, UUID> {

    interface TransactionBalanceProjection {
        UUID getId();

        java.math.BigDecimal getBalance();
    }

    @Override
    @EntityGraph(attributePaths = { "categories.category", "account" })
    Page<Transaction> findAll(Pageable pageable);

    @Query(value = """
            SELECT sub.id, sub.balance, sub.transaction_date, sub.created_at FROM (
                SELECT t.id,
                       t.transaction_date,
                       t.created_at,
                       (COALESCE(abd.opening_balance, 0) +
                        SUM(CASE WHEN t.type = 'CREDIT' THEN t.amount ELSE -t.amount END)
                        OVER (PARTITION BY t.account_id ORDER BY t.transaction_date ASC, t.created_at ASC, t.id ASC)) as balance
                FROM transactions t
                LEFT JOIN account_bank_details abd ON t.account_id = abd.account_id
                WHERE t.user_id = :userId
            ) sub
            """, countQuery = "SELECT count(*) FROM transactions WHERE user_id = :userId", nativeQuery = true)
    Page<TransactionBalanceProjection> findIdsWithRunningBalance(@Param("userId") String userId, Pageable pageable);

    @EntityGraph(attributePaths = { "categories.category", "account" })
    List<Transaction> findAllByIdIn(List<UUID> ids);

    @EntityGraph(attributePaths = { "categories.category", "account" })
    Page<Transaction> findByAccountId(UUID accountId, Pageable pageable);

    @Query("SELECT t FROM Transaction t WHERE t.date BETWEEN :startDate AND :endDate ORDER BY t.date DESC")
    List<Transaction> findByDateRange(@Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate);

    @Query("SELECT t FROM Transaction t JOIN t.categories tc JOIN tc.category c WHERE c.name = :category ORDER BY t.date DESC")
    List<Transaction> findByCategory(@Param("category") String category);
}
