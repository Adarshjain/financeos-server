package com.financeos.domain.loan;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface LoanEventRepository extends JpaRepository<LoanEvent, UUID> {

    List<LoanEvent> findByLoan_IdOrderByEffectiveDateAscCreatedAtAsc(UUID loanId);

    boolean existsByTransaction_Id(UUID transactionId);
}
