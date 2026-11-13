package com.financeos.domain.user;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserRepository extends JpaRepository<User, UUID> {

    /** The user's row locked for update: serialises work done for one user across the nightly run and an import job's run. */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM User u WHERE u.id = :id")
    Optional<User> lockById(@Param("id") UUID id);

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    Optional<User> findByGoogleId(String googleId);

    /**
     * Claims the one-shot Home-dashboard seed for {@code id}: returns 1 for the caller that
     * flipped {@code homeSeededAt} from null, 0 when it was already set (or the user is unknown).
     * Concurrent first requests serialize on the row, so exactly one caller gets 1.
     */
    @Modifying
    @Transactional
    @Query("UPDATE User u SET u.homeSeededAt = :now WHERE u.id = :id AND u.homeSeededAt IS NULL")
    int markHomeSeeded(@Param("id") UUID id, @Param("now") Instant now);

    /** Users whose one-time auto-link history backfill has not run yet. */
    /** Every user id, in id order (system jobs that walk all users). */
    @Query("SELECT u.id FROM User u ORDER BY u.id")
    java.util.List<UUID> findAllIds();

    @Query("SELECT u.id FROM User u WHERE u.autoLinkBackfilledAt IS NULL")
    java.util.List<UUID> findIdsPendingAutoLinkBackfill();

    @Modifying
    @Transactional
    @Query("UPDATE User u SET u.autoLinkBackfilledAt = :now WHERE u.id = :id AND u.autoLinkBackfilledAt IS NULL")
    int markAutoLinkBackfilled(@Param("id") UUID id, @Param("now") Instant now);
}
