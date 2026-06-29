package com.financeos.domain.report;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Reports are auto-scoped to the current user by the {@code userFilter}, so the
 * standard finders already return only the session user's reports.
 */
@Repository
public interface ReportRepository extends JpaRepository<Report, UUID> {

    List<Report> findAllByType(ReportType type);
}
