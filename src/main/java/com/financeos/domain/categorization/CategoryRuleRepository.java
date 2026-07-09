package com.financeos.domain.categorization;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CategoryRuleRepository extends JpaRepository<CategoryRule, UUID> {

    Optional<CategoryRule> findByUserIdAndMerchantKey(UUID userId, String merchantKey);

    List<CategoryRule> findByUserId(UUID userId);

    @EntityGraph(attributePaths = "categories")
    @Query("SELECT r FROM CategoryRule r WHERE r.user.id = :userId " +
           "AND (:verified IS NULL OR r.verified = :verified) " +
           "AND (:search IS NULL OR LOWER(r.merchantKey) LIKE LOWER(CONCAT('%', CONCAT(:search, '%'))) " +
           "OR LOWER(r.displayName) LIKE LOWER(CONCAT('%', CONCAT(:search, '%'))))")
    Page<CategoryRule> findRules(
            @Param("userId") UUID userId,
            @Param("verified") Boolean verified,
            @Param("search") String search,
            Pageable pageable);

    @EntityGraph(attributePaths = "categories")
    Optional<CategoryRule> findWithCategoriesById(UUID id);
}
