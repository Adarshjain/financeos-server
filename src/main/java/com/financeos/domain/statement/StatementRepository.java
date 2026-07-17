package com.financeos.domain.statement;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Repository
public interface StatementRepository extends JpaRepository<Statement, UUID> {

    boolean existsByAccountIdAndPeriodStartAndPeriodEnd(UUID accountId, LocalDate periodStart, LocalDate periodEnd);

    boolean existsByAccountIdAndFileSha256(UUID accountId, String fileSha256);

    @Query("SELECT s FROM Statement s WHERE s.account.id = :accountId ORDER BY s.periodEnd DESC NULLS LAST, s.createdAt DESC")
    List<Statement> findByAccountIdOrderByPeriodEndDescNullsLast(@Param("accountId") UUID accountId);
}
