package com.financeos.domain.loan;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface LoanRepository extends JpaRepository<Loan, UUID> {

    Page<Loan> findByStatus(LoanStatus status, Pageable pageable);

    List<Loan> findByStatus(LoanStatus status);

    @Query("SELECT COUNT(le) FROM LoanEvent le WHERE le.loan.id = :loanId")
    long countEventsByLoanId(@Param("loanId") UUID loanId);

    @Query("SELECT COUNT(lp) FROM LoanPayment lp WHERE lp.loan.id = :loanId")
    long countPaymentsByLoanId(@Param("loanId") UUID loanId);
}
