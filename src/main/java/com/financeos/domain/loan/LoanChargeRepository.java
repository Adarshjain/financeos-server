package com.financeos.domain.loan;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Collection;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface LoanChargeRepository extends JpaRepository<LoanCharge, UUID> {

    List<LoanCharge> findByLoan_IdOrderByChargeDateAscCreatedAtAsc(UUID loanId);

    boolean existsByTransaction_Id(UUID transactionId);

    @Query("SELECT c FROM LoanCharge c JOIN FETCH c.loan WHERE c.transaction.id IN :ids")
    List<LoanCharge> findWithLoanByTransactionIdIn(@Param("ids") Collection<UUID> ids);
}
