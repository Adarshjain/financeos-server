package com.financeos.domain.instrument.corporateaction.detect;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Every read names the owner: jobs run without the Hibernate user filter on a request. */
@Repository
public interface CorporateActionSuggestionRepository extends JpaRepository<CorporateActionSuggestion, UUID> {

    /** All of {@code userId}'s suggestions, instruments fetched. */
    @Query("SELECT s FROM CorporateActionSuggestion s JOIN FETCH s.instrument WHERE s.user.id = :userId")
    List<CorporateActionSuggestion> findAllOwned(@Param("userId") UUID userId);

    /** One of {@code userId}'s suggestions with instrument and linked action fetched (another user's id is empty). */
    @Query("SELECT s FROM CorporateActionSuggestion s JOIN FETCH s.instrument LEFT JOIN FETCH s.corporateAction "
            + "WHERE s.id = :id AND s.user.id = :userId")
    Optional<CorporateActionSuggestion> findOwnedById(@Param("id") UUID id, @Param("userId") UUID userId);

    /** {@code userId}'s pending suggestions of the given kinds, instruments fetched, newest ex-date first. */
    @Query("SELECT s FROM CorporateActionSuggestion s JOIN FETCH s.instrument "
            + "WHERE s.user.id = :userId AND s.status = com.financeos.domain.instrument.corporateaction.detect.SuggestionStatus.pending "
            + "AND s.kind IN :kinds "
            + "AND NOT EXISTS (SELECT 1 FROM CorporateAction ca WHERE ca.instrument.id = s.instrument.id "
            + "AND ca.exDate = s.exDate AND ca.user.id = :userId) ORDER BY s.exDate DESC")
    List<CorporateActionSuggestion> findPendingOfKinds(@Param("userId") UUID userId,
                                                       @Param("kinds") java.util.Collection<SuggestionKind> kinds);

    /** {@code userId}'s suggestions in {@code status}, instruments fetched, newest ex-date first. */
    @Query("SELECT s FROM CorporateActionSuggestion s JOIN FETCH s.instrument "
            + "WHERE s.user.id = :userId AND s.status = :status ORDER BY s.exDate DESC, s.createdAt DESC")
    List<CorporateActionSuggestion> findOwnedByStatus(@Param("userId") UUID userId, @Param("status") SuggestionStatus status);

    /**
     * {@code userId}'s pending suggestions of every kind that still need a decision: those with no corporate
     * action already recorded for the same instrument and ex-date (the same rule as {@link #findPendingOfKinds}),
     * instruments fetched, newest ex-date first.
     */
    @Query("SELECT s FROM CorporateActionSuggestion s JOIN FETCH s.instrument "
            + "WHERE s.user.id = :userId AND s.status = com.financeos.domain.instrument.corporateaction.detect.SuggestionStatus.pending "
            + "AND NOT EXISTS (SELECT 1 FROM CorporateAction ca WHERE ca.instrument.id = s.instrument.id "
            + "AND ca.exDate = s.exDate AND ca.user.id = :userId) ORDER BY s.exDate DESC, s.createdAt DESC")
    List<CorporateActionSuggestion> findPendingUnresolved(@Param("userId") UUID userId);

    /**
     * Every user's pending suggestions of one kind, instruments fetched, newest ex-date first. For the worker's
     * market-wide notice reading only: it needs the distinct (stock, ex-date) pairs, never a user's data.
     */
    @Query("SELECT s FROM CorporateActionSuggestion s JOIN FETCH s.instrument "
            + "WHERE s.status = com.financeos.domain.instrument.corporateaction.detect.SuggestionStatus.pending "
            + "AND s.kind = :kind ORDER BY s.exDate DESC")
    List<CorporateActionSuggestion> findAllPendingOfKind(@Param("kind") SuggestionKind kind);

    /**
     * One of {@code userId}'s suggestions locked for update (SELECT ... FOR UPDATE), so a concurrent run waits and then sees its
     * latest status. No fetch joins: a lock cannot span an outer join.
     */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM CorporateActionSuggestion s WHERE s.id = :id AND s.user.id = :userId")
    Optional<CorporateActionSuggestion> lockOwnedById(@Param("id") UUID id, @Param("userId") UUID userId);

    /** {@code userId}'s suggestions of one kind for one instrument, any status, newest first. */
    @Query("SELECT s FROM CorporateActionSuggestion s WHERE s.user.id = :userId AND s.instrument.id = :instrumentId AND s.kind = :kind "
            + "ORDER BY s.createdAt DESC")
    List<CorporateActionSuggestion> findOwnedOfKindForInstrument(@Param("userId") UUID userId, @Param("instrumentId") UUID instrumentId,
                                                                 @Param("kind") SuggestionKind kind);
}
