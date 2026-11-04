package com.financeos.domain.statement.skip;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface StatementSkippedPeriodRepository extends JpaRepository<StatementSkippedPeriod, UUID> {

    interface SkippedEnd {
        UUID getAccountId();
        LocalDate getPeriodEnd();
    }

    /** The skipped period ends of several accounts, no entities (one query for every card of a list). */
    @Query("SELECT s.account.id AS accountId, s.periodEnd AS periodEnd FROM StatementSkippedPeriod s WHERE s.account.id IN :accountIds")
    List<SkippedEnd> findEndsByAccountIds(@Param("accountIds") Collection<UUID> accountIds);

    List<StatementSkippedPeriod> findByAccountIdOrderByPeriodEndDesc(UUID accountId);

    Optional<StatementSkippedPeriod> findByAccountIdAndPeriodEnd(UUID accountId, LocalDate periodEnd);

    Optional<StatementSkippedPeriod> findByIdAndAccountId(UUID id, UUID accountId);
}
