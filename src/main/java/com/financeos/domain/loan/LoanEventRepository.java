package com.financeos.domain.loan;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Collection;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface LoanEventRepository extends JpaRepository<LoanEvent, UUID> {

    List<LoanEvent> findByLoan_IdOrderByEffectiveDateAscCreatedAtAsc(UUID loanId);

    boolean existsByTransaction_Id(UUID transactionId);

    @Query("SELECT e FROM LoanEvent e JOIN FETCH e.loan WHERE e.transaction.id IN :ids")
    List<LoanEvent> findWithLoanByTransactionIdIn(@Param("ids") Collection<UUID> ids);
}
