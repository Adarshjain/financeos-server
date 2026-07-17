package com.financeos.domain.statement;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface StatementTransactionRepository extends JpaRepository<StatementTransaction, StatementTransactionId> {
}
