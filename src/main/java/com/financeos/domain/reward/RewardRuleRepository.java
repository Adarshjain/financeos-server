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

    long countByCapBucketId(UUID capBucketId);

    long countByAccountId(UUID accountId);

    long countByCardholderId(UUID cardholderId);

    @Query("SELECT DISTINCT r.account.id FROM RewardRule r WHERE r.user.id = :userId")
    List<UUID> findDistinctAccountIdsByUserId(@Param("userId") UUID userId);
}

