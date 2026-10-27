package com.financeos.domain.epf;

import com.financeos.domain.transaction.Transaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface EpfEntryRepository extends JpaRepository<EpfEntry, UUID> {

    /** All of a user's entries with employer and linked bank credit (and its account), in one query. */
    @Query("SELECT e FROM EpfEntry e JOIN FETCH e.employer LEFT JOIN FETCH e.transaction t LEFT JOIN FETCH t.account "
            + "WHERE e.user.id = :userId")
    List<EpfEntry> findAllForUser(@Param("userId") UUID userId);

    /** Reverse lookup for transaction responses (batch, one query). */
    @Query("SELECT e FROM EpfEntry e JOIN FETCH e.employer WHERE e.transaction.id IN :ids")
    List<EpfEntry> findWithEmployerByTransactionIdIn(@Param("ids") Collection<UUID> ids);

    /**
     * The user's bank credits (money in) dated within the window, with their accounts, that no loan, lending,
     * dividend or EPF record references yet.
     */
    @Query("SELECT t FROM Transaction t JOIN FETCH t.account WHERE t.user.id = :userId "
            + "AND t.type = com.financeos.domain.transaction.TransactionType.CREDIT "
            + "AND t.date >= :from AND t.date <= :to "
            + "AND NOT EXISTS (SELECT 1 FROM Lending l WHERE l.transaction.id = t.id) "
            + "AND NOT EXISTS (SELECT 1 FROM LoanPayment p WHERE p.transaction.id = t.id) "
            + "AND NOT EXISTS (SELECT 1 FROM LoanEvent ev WHERE ev.transaction.id = t.id) "
            + "AND NOT EXISTS (SELECT 1 FROM LoanCharge c WHERE c.transaction.id = t.id) "
            + "AND NOT EXISTS (SELECT 1 FROM Dividend d WHERE d.transaction.id = t.id) "
            + "AND NOT EXISTS (SELECT 1 FROM EpfEntry x WHERE x.transaction.id = t.id)")
    List<Transaction> findCreditsBetween(@Param("userId") UUID userId, @Param("from") LocalDate from,
                                         @Param("to") LocalDate to);
}
