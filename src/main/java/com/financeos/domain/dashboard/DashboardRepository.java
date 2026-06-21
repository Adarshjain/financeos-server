package com.financeos.domain.dashboard;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/** Dashboards are auto-scoped to the current user by the {@code userFilter}. */
@Repository
public interface DashboardRepository extends JpaRepository<Dashboard, UUID> {
}
