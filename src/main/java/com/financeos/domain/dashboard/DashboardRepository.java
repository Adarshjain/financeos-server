package com.financeos.domain.dashboard;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/** Dashboards are auto-scoped to the current user by the {@code userFilter} on SELECT queries. */
@Repository
public interface DashboardRepository extends JpaRepository<Dashboard, UUID> {

    /** The current user's default dashboard (userFilter scopes the select), if any. */
    @Query("SELECT d FROM Dashboard d WHERE d.isDefault = true")
    Optional<Dashboard> findDefault();

    /**
     * Clears the default flag for the user's dashboards. The Hibernate userFilter does NOT apply
     * to bulk updates, so the user is scoped explicitly here.
     */
    @Modifying
    @Query("UPDATE Dashboard d SET d.isDefault = false WHERE d.user.id = :userId AND d.isDefault = true")
    void clearDefaultForUser(@Param("userId") UUID userId);
}
