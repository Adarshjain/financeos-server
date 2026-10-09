package com.financeos.domain.categorization;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CategoryRuleRepository extends JpaRepository<CategoryRule, UUID> {

    Optional<CategoryRule> findByUserIdAndMerchantKeyAndMatchType(UUID userId, String merchantKey, MatchType matchType);

    List<CategoryRule> findByUserId(UUID userId);

    /**
     * One page of rules with their categories loaded. Paging runs in SQL on the rule rows, then a
     * second query loads categories for just that page. Fetch-joining the collection in the paged
     * query made Hibernate fetch every matching rule and page in memory (HHH90003004).
     */
    @Transactional(readOnly = true)
    default Page<CategoryRule> findRules(UUID userId, Boolean verified, String search, String source,
                                         MatchType matchType, Integer minApplied, Integer maxApplied,
                                         UUID categoryId, Pageable pageable) {
        Page<CategoryRule> page = findRulePage(userId, verified, search, source, matchType,
                minApplied, maxApplied, categoryId, pageable);
        if (!page.isEmpty()) {
            // Same persistence context: this initializes categories on the page's own instances.
            findWithCategoriesByIdIn(page.getContent().stream().map(CategoryRule::getId).toList());
        }
        return page;
    }

    @Query("SELECT r FROM CategoryRule r WHERE r.user.id = :userId " +
           "AND (:verified IS NULL OR r.verified = :verified) " +
           "AND (:search IS NULL OR LOWER(r.merchantKey) LIKE LOWER(CONCAT('%', CONCAT(:search, '%'))) " +
           "OR LOWER(r.displayName) LIKE LOWER(CONCAT('%', CONCAT(:search, '%')))) " +
           "AND (:source IS NULL OR r.source = :source) " +
           "AND (:matchType IS NULL OR r.matchType = :matchType) " +
           "AND (:minApplied IS NULL OR r.appliedCount >= :minApplied) " +
           "AND (:maxApplied IS NULL OR r.appliedCount <= :maxApplied) " +
           "AND (:categoryId IS NULL OR r.id IN " +
           "(SELECT r2.id FROM CategoryRule r2 JOIN r2.categories c WHERE c.id = :categoryId))")
    Page<CategoryRule> findRulePage(
            @Param("userId") UUID userId,
            @Param("verified") Boolean verified,
            @Param("search") String search,
            @Param("source") String source,
            @Param("matchType") MatchType matchType,
            @Param("minApplied") Integer minApplied,
            @Param("maxApplied") Integer maxApplied,
            @Param("categoryId") UUID categoryId,
            Pageable pageable);

    @EntityGraph(attributePaths = "categories")
    Optional<CategoryRule> findWithCategoriesById(UUID id);

    @EntityGraph(attributePaths = "categories")
    List<CategoryRule> findWithCategoriesByIdIn(Collection<UUID> ids);

    List<CategoryRule> findByUserIdAndIdIn(UUID userId, Collection<UUID> ids);
}
