package com.financeos.domain.loan;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LoanPaymentRepository extends JpaRepository<LoanPayment, UUID> {

    List<LoanPayment> findByLoan_IdOrderByInstallmentSeqAsc(UUID loanId);

    Optional<LoanPayment> findByLoan_IdAndInstallmentSeq(UUID loanId, Integer installmentSeq);

    boolean existsByLoan_IdAndInstallmentSeq(UUID loanId, Integer installmentSeq);

    boolean existsByTransaction_Id(UUID transactionId);
}
