package com.financeos.domain.loan;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface LoanRepository extends JpaRepository<Loan, UUID> {

    /** Whether the user has at least one row. */
    boolean existsByUser_Id(UUID userId);


    Page<Loan> findByStatus(LoanStatus status, Pageable pageable);

    List<Loan> findByStatus(LoanStatus status);

    /** Case-insensitive contains (pre-lowered LIKE pattern, backslash-escaped) on name, lender or account number. */
    @Query("SELECT l FROM Loan l WHERE l.status = :status AND (LOWER(l.name) LIKE :pattern ESCAPE '\\' "
            + "OR LOWER(l.lender) LIKE :pattern ESCAPE '\\' OR LOWER(l.loanAccountNumber) LIKE :pattern ESCAPE '\\')")
    Page<Loan> searchByStatus(@Param("status") LoanStatus status, @Param("pattern") String pattern, Pageable pageable);

    @Query("SELECT l FROM Loan l WHERE (LOWER(l.name) LIKE :pattern ESCAPE '\\' "
            + "OR LOWER(l.lender) LIKE :pattern ESCAPE '\\' OR LOWER(l.loanAccountNumber) LIKE :pattern ESCAPE '\\')")
    Page<Loan> search(@Param("pattern") String pattern, Pageable pageable);

    /** Loans the user muted for EMI pushes (notification settings view). */
    List<Loan> findByUser_IdAndNotificationsMutedTrue(UUID userId);

    @Query("SELECT COUNT(le) FROM LoanEvent le WHERE le.loan.id = :loanId")
    long countEventsByLoanId(@Param("loanId") UUID loanId);

    @Query("SELECT COUNT(lp) FROM LoanPayment lp WHERE lp.loan.id = :loanId")
    long countPaymentsByLoanId(@Param("loanId") UUID loanId);

    /** Loans paid from the given account that have the given status. */
    List<Loan> findByPaymentAccount_IdAndStatus(UUID paymentAccountId, LoanStatus status);
}
