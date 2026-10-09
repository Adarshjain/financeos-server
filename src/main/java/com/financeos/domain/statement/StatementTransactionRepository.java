package com.financeos.domain.statement;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Repository
public interface StatementTransactionRepository extends JpaRepository<StatementTransaction, StatementTransactionId> {

    @Query("SELECT new com.financeos.domain.statement.StatementLineProjection(" +
           "st.id.transactionId, st.lineIndex, t.date, " +
           "COALESCE(t.sourcedDescription, t.description), t.amount, t.type, t.reviewType, " +
           "st.balanceAfter, st.chainValid) " +
           "FROM StatementTransaction st, Transaction t " +
           "WHERE st.id.statementId = :statementId AND t.id = st.id.transactionId " +
           "ORDER BY st.lineIndex ASC")
    List<StatementLineProjection> findLinesByStatementId(@Param("statementId") UUID statementId);
    List<StatementTransaction> findByIdTransactionId(UUID transactionId);

    @Query("SELECT DISTINCT st.id.transactionId FROM StatementTransaction st, Transaction t " +
           "WHERE t.id = st.id.transactionId AND t.account.id = :accountId " +
           "AND t.date BETWEEN :startDate AND :endDate")
    Set<UUID> findLinkedTransactionIds(@Param("accountId") UUID accountId,
                                       @Param("startDate") LocalDate startDate,
                                       @Param("endDate") LocalDate endDate);
}

