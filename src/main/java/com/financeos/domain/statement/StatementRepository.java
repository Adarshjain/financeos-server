package com.financeos.domain.statement;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.UUID;

@Repository
public interface StatementRepository extends JpaRepository<Statement, UUID> {

    boolean existsByAccountIdAndPeriodStartAndPeriodEnd(UUID accountId, LocalDate periodStart, LocalDate periodEnd);

    boolean existsByAccountIdAndFileSha256(UUID accountId, String fileSha256);
}
