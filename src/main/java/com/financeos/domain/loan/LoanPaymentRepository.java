package com.financeos.domain.loan;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Collection;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LoanPaymentRepository extends JpaRepository<LoanPayment, UUID> {

    List<LoanPayment> findByLoan_IdOrderByInstallmentSeqAsc(UUID loanId);

    Optional<LoanPayment> findByLoan_IdAndInstallmentSeq(UUID loanId, Integer installmentSeq);

    boolean existsByLoan_IdAndInstallmentSeq(UUID loanId, Integer installmentSeq);

    boolean existsByTransaction_Id(UUID transactionId);

    @Query("SELECT p FROM LoanPayment p JOIN FETCH p.loan WHERE p.transaction.id IN :ids")
    List<LoanPayment> findWithLoanByTransactionIdIn(@Param("ids") Collection<UUID> ids);
}
