package com.financeos.domain.categorization;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CategoryRuleRepository extends JpaRepository<CategoryRule, UUID> {

    Optional<CategoryRule> findByUserIdAndMerchantKeyAndMatchType(UUID userId, String merchantKey, MatchType matchType);

    List<CategoryRule> findByUserId(UUID userId);

    @Query("SELECT r FROM CategoryRule r WHERE r.user.id = :userId " +
           "AND (:search IS NULL OR LOWER(r.merchantKey) LIKE LOWER(CONCAT('%', CONCAT(:search, '%'))) " +
           "OR LOWER(r.displayName) LIKE LOWER(CONCAT('%', CONCAT(:search, '%')))) " +
           "AND (:matchType IS NULL OR r.matchType = :matchType) " +
           "AND (:minApplied IS NULL OR r.appliedCount >= :minApplied) " +
           "AND (:maxApplied IS NULL OR r.appliedCount <= :maxApplied) " +
           "AND (:categoryKey IS NULL OR r.categoryKey = :categoryKey)")
    Page<CategoryRule> findRules(
            @Param("userId") UUID userId,
            @Param("search") String search,
            @Param("matchType") MatchType matchType,
            @Param("minApplied") Integer minApplied,
            @Param("maxApplied") Integer maxApplied,
            @Param("categoryKey") String categoryKey,
            Pageable pageable);

    List<CategoryRule> findByUserIdAndIdIn(UUID userId, Collection<UUID> ids);
}
