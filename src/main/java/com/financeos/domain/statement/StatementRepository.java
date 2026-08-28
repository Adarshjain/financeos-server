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

    @Query("SELECT COUNT(s) > 0 FROM Statement s WHERE s.account.id = :accountId AND ((:cardId IS NULL AND s.card IS NULL) OR (s.card.id = :cardId)) AND s.periodStart = :periodStart AND s.periodEnd = :periodEnd")
    boolean existsByAccountIdAndCardIdAndPeriodStartAndPeriodEnd(
            @Param("accountId") UUID accountId,
            @Param("cardId") UUID cardId,
            @Param("periodStart") LocalDate periodStart,
            @Param("periodEnd") LocalDate periodEnd);

    boolean existsByAccountIdAndFileSha256(UUID accountId, String fileSha256);

    @Query("SELECT s FROM Statement s WHERE s.account.id = :accountId ORDER BY s.periodEnd DESC NULLS LAST, s.createdAt DESC")
    List<Statement> findByAccountIdOrderByPeriodEndDescNullsLast(@Param("accountId") UUID accountId);

    interface AnchorStatementProjection {
        UUID getId();
        LocalDate getPeriodEnd();
        java.math.BigDecimal getClosingBalance();
    }

    @Query("SELECT s FROM Statement s JOIN FETCH s.creditCardDetails d WHERE s.account.id = :accountId AND (s.card IS NULL OR s.card.isPrimary = true) AND s.statementType = 'credit_card' AND s.verdict != com.financeos.domain.statement.StatementVerdict.REJECTED ORDER BY s.periodEnd ASC NULLS LAST, s.createdAt ASC")
    List<Statement> findQualifyingCreditCardStatements(@Param("accountId") UUID accountId);

    @Query("SELECT s.id AS id, s.periodEnd AS periodEnd, s.closingBalance AS closingBalance FROM Statement s WHERE s.account.id = :accountId AND (s.card IS NULL OR s.card.isPrimary = true) AND s.periodEnd IS NOT NULL AND s.closingBalance IS NOT NULL AND s.verdict != com.financeos.domain.statement.StatementVerdict.REJECTED ORDER BY s.periodEnd DESC, s.createdAt DESC")
    List<AnchorStatementProjection> findEligibleAnchorStatements(@Param("accountId") UUID accountId, org.springframework.data.domain.Pageable pageable);
}
