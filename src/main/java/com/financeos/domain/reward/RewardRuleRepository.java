package com.financeos.domain.reward;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RewardRuleRepository extends JpaRepository<RewardRule, UUID> {

    @EntityGraph(attributePaths = {"categories"})
    Optional<RewardRule> findWithCategoriesById(UUID id);

    /** All rules of an account, highest priority first (evaluation order). */
    @Query("SELECT r FROM RewardRule r WHERE r.account.id = :accountId ORDER BY r.priority DESC, r.createdAt ASC")
    List<RewardRule> findByAccountIdOrderByPriorityDesc(@Param("accountId") UUID accountId);

    /** Rules of several accounts, in evaluation order per account; categories fetched in the same query (batched totals). */
    @EntityGraph(attributePaths = {"categories"})
    @Query("SELECT r FROM RewardRule r WHERE r.account.id IN :accountIds ORDER BY r.priority DESC, r.createdAt ASC")
    List<RewardRule> findByAccountIdInOrderByPriorityDesc(@Param("accountIds") java.util.Collection<UUID> accountIds);

    long countByCapBucketId(UUID capBucketId);

    long countByAccountId(UUID accountId);

    /** Rule counts for several accounts as (accountId, count) rows; accounts without rules are absent (one query, not one per account). */
    @Query("SELECT r.account.id, COUNT(r) FROM RewardRule r WHERE r.account.id IN :accountIds GROUP BY r.account.id")
    List<Object[]> countGroupedByAccountId(@Param("accountIds") java.util.Collection<UUID> accountIds);

    long countByCardholderId(UUID cardholderId);

    @Query("SELECT DISTINCT r.account.id FROM RewardRule r WHERE r.user.id = :userId")
    List<UUID> findDistinctAccountIdsByUserId(@Param("userId") UUID userId);
}

