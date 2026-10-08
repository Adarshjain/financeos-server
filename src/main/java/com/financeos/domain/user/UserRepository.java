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
}
