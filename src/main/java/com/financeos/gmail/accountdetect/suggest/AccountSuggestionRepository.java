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

    /** The user's suggestions, all of them (e2e reset). */
    @Modifying
    @Query("DELETE FROM AccountSuggestion s WHERE s.userId = :userId")
    int deleteForUser(@Param("userId") UUID userId);
}
