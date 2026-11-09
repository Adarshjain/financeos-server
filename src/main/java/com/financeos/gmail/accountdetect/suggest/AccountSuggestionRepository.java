package com.financeos.gmail.accountdetect.suggest;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AccountSuggestionRepository extends JpaRepository<AccountSuggestion, UUID> {

    List<AccountSuggestion> findByUserId(UUID userId);

    List<AccountSuggestion> findByUserIdAndState(UUID userId, AccountSuggestion.State state);

    Optional<AccountSuggestion> findByIdAndUserId(UUID id, UUID userId);

    /**
     * The atomic claim: NEW to ADDED for exactly this user's row. The row lock is held to the end of the caller's
     * transaction, so two creates from one suggestion cannot both win, and a failed create rolls the claim back.
     */
    @Modifying
    @Query("UPDATE AccountSuggestion s SET s.state = com.financeos.gmail.accountdetect.suggest.AccountSuggestion.State.ADDED, "
            + "s.updatedAt = :now WHERE s.id = :id AND s.userId = :userId "
            + "AND s.state = com.financeos.gmail.accountdetect.suggest.AccountSuggestion.State.NEW")
    int claimNew(@Param("id") UUID id, @Param("userId") UUID userId, @Param("now") java.time.Instant now);

    /** The atomic claim for "belongs to an account": NEW to BELONGS for exactly this user's row, same locking as {@link #claimNew}. */
    @Modifying
    @Query("UPDATE AccountSuggestion s SET s.state = com.financeos.gmail.accountdetect.suggest.AccountSuggestion.State.BELONGS, "
            + "s.updatedAt = :now WHERE s.id = :id AND s.userId = :userId "
            + "AND s.state = com.financeos.gmail.accountdetect.suggest.AccountSuggestion.State.NEW")
    int claimBelongs(@Param("id") UUID id, @Param("userId") UUID userId, @Param("now") java.time.Instant now);

    /** Deletes these rows only while still NEW and the user's own (an answer given since they were read wins). */
    @Modifying
    @Query("DELETE FROM AccountSuggestion s WHERE s.userId = :userId AND s.id IN :ids "
            + "AND s.state = com.financeos.gmail.accountdetect.suggest.AccountSuggestion.State.NEW")
    int deleteNewByIds(@Param("userId") UUID userId, @Param("ids") java.util.Collection<UUID> ids);

    /** The user's suggestions, all of them (e2e reset). */
    @Modifying
    @Query("DELETE FROM AccountSuggestion s WHERE s.userId = :userId")
    int deleteForUser(@Param("userId") UUID userId);
}
