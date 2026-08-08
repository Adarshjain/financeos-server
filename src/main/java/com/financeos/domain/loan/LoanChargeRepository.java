package com.financeos.domain.loan;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface LoanChargeRepository extends JpaRepository<LoanCharge, UUID> {

    List<LoanCharge> findByLoan_IdOrderByChargeDateAscCreatedAtAsc(UUID loanId);

    boolean existsByTransaction_Id(UUID transactionId);
}
