package com.financeos.domain.statement;

import com.financeos.api.statement.dto.StatementLineResponse;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface StatementTransactionRepository extends JpaRepository<StatementTransaction, StatementTransactionId> {

    @Query("SELECT new com.financeos.api.statement.dto.StatementLineResponse(" +
           "st.id.transactionId, st.lineIndex, t.date, " +
           "COALESCE(t.sourcedDescription, t.description), t.amount, t.type, t.reviewType, " +
           "st.balanceAfter, st.chainValid) " +
           "FROM StatementTransaction st, Transaction t " +
           "WHERE st.id.statementId = :statementId AND t.id = st.id.transactionId " +
           "ORDER BY st.lineIndex ASC")
    List<StatementLineResponse> findLinesByStatementId(@Param("statementId") UUID statementId);
}
