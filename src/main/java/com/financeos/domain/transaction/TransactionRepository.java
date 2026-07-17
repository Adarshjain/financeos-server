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
public interface TransactionRepository extends JpaRepository<Transaction, UUID>, TransactionRepositoryCustom {

    interface TransactionBalanceProjection {
        UUID getId();

        java.math.BigDecimal getBalance();
    }

    @Override
    @EntityGraph(attributePaths = { "categories.category", "account" })
    Page<Transaction> findAll(Pageable pageable);


    @EntityGraph(attributePaths = { "categories.category", "account", "reviewReasons" })
    List<Transaction> findAllByIdIn(List<UUID> ids);

    @EntityGraph(attributePaths = { "categories.category", "account" })
    Page<Transaction> findByAccountId(UUID accountId, Pageable pageable);

    @Query("SELECT t FROM Transaction t WHERE t.date BETWEEN :startDate AND :endDate ORDER BY t.date DESC")
    List<Transaction> findByDateRange(@Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate);

    @Query("SELECT t FROM Transaction t JOIN t.categories tc JOIN tc.category c WHERE c.name = :category ORDER BY t.date DESC")
    List<Transaction> findByCategory(@Param("category") String category);

    boolean existsBySourceMessageId(String sourceMessageId);

    @Query("SELECT t FROM Transaction t WHERE t.account.id = :accountId AND t.source = :source AND t.reviewType = :reviewType")
    List<Transaction> findByAccountIdAndSourceAndReviewType(
            @Param("accountId") UUID accountId,
            @Param("source") TransactionSource source,
            @Param("reviewType") ReviewType reviewType);

    @Query("SELECT t FROM Transaction t WHERE t.account.id = :accountId AND t.date BETWEEN :startDate AND :endDate")
    List<Transaction> findByAccountIdAndDateRange(
            @Param("accountId") UUID accountId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate);

    List<Transaction> findByAppliedRuleId(UUID appliedRuleId);
}
