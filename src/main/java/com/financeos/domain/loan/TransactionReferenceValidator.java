package com.financeos.domain.loan;

import com.financeos.core.exception.ValidationException;
import com.financeos.core.security.UserContext;
import com.financeos.domain.lending.LendingDirection;
import com.financeos.domain.lending.LendingRepository;
import com.financeos.domain.transaction.Transaction;
import com.financeos.domain.transaction.TransactionRepository;
import com.financeos.domain.transaction.TransactionType;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Guards the direct transaction FKs held by loan and lending rows.
 *
 * <p>Rules (plans/lending-transaction-linking-plan.md §3.1):
 * <ul>
 *   <li>Loan rows are 1:1 with a transaction and exclusive across all four tables
 *       ({@link #validateForLoan}).</li>
 *   <li>Lending rows may share one transaction (split bills) but never a transaction that a loan
 *       row already references, and the entry direction must match the money direction:
 *       {@code lent} ↔ DEBIT, {@code borrowed} ↔ CREDIT ({@link #validateForLending}).</li>
 * </ul>
 */
@Component
public class TransactionReferenceValidator {

    private static final Logger log = LoggerFactory.getLogger(TransactionReferenceValidator.class);

    private final TransactionRepository transactionRepository;
    private final LoanEventRepository loanEventRepository;
    private final LoanPaymentRepository loanPaymentRepository;
    private final LoanChargeRepository loanChargeRepository;
    private final LendingRepository lendingRepository;

    @PersistenceContext
    private EntityManager entityManager;

    public TransactionReferenceValidator(
            TransactionRepository transactionRepository,
            LoanEventRepository loanEventRepository,
            LoanPaymentRepository loanPaymentRepository,
            LoanChargeRepository loanChargeRepository,
            LendingRepository lendingRepository) {
        this.transactionRepository = transactionRepository;
        this.loanEventRepository = loanEventRepository;
        this.loanPaymentRepository = loanPaymentRepository;
        this.loanChargeRepository = loanChargeRepository;
        this.lendingRepository = lendingRepository;
    }

    /** Loan payments / events / charges: owned, and referenced by nothing else (loans or lendings). */
    public Transaction validateForLoan(UUID transactionId) {
        if (transactionId == null) {
            return null;
        }
        Transaction transaction = loadOwned(transactionId);
        if (isTransactionReferenced(transactionId)) {
            throw new ValidationException("Transaction " + transactionId + " is already linked to a loan or lending record");
        }
        return transaction;
    }

    /**
     * Lending ledger entries: owned, not referenced by any loan row, and direction-consistent.
     * Other lending entries on the same transaction are allowed (split bills).
     */
    public Transaction validateForLending(UUID transactionId, LendingDirection direction) {
        if (transactionId == null) {
            return null;
        }
        Transaction transaction = loadOwned(transactionId);
        if (isReferencedByLoan(transactionId)) {
            throw new ValidationException("Transaction " + transactionId + " is already linked to a loan record");
        }
        TransactionType expected = expectedTypeFor(direction);
        if (direction != null && transaction.getType() != expected) {
            String want = expected == TransactionType.DEBIT ? "a DEBIT (money out)" : "a CREDIT (money in)";
            throw new ValidationException("A '" + direction + "' entry must link " + want + " transaction");
        }
        return transaction;
    }

    public static TransactionType expectedTypeFor(LendingDirection direction) {
        return direction == LendingDirection.borrowed ? TransactionType.CREDIT : TransactionType.DEBIT;
    }

    public boolean isReferencedByLoan(UUID transactionId) {
        if (transactionId == null) {
            return false;
        }
        return loanEventRepository.existsByTransaction_Id(transactionId)
                || loanPaymentRepository.existsByTransaction_Id(transactionId)
                || loanChargeRepository.existsByTransaction_Id(transactionId);
    }

    public boolean isReferencedByLending(UUID transactionId) {
        return transactionId != null && lendingRepository.existsByTransaction_Id(transactionId);
    }

    public boolean isTransactionReferenced(UUID transactionId) {
        return isReferencedByLoan(transactionId) || isReferencedByLending(transactionId);
    }

    /**
     * Every transaction id referenced by any loan/lending row. Used only as an exclusion set for
     * match suggestions — deliberately NOT user-filtered, so never surface it to a client.
     */
    @SuppressWarnings("unchecked")
    public Set<UUID> getAllReferencedTransactionIds() {
        Set<UUID> ids = new HashSet<>();
        String sql = "SELECT transaction_id FROM loan_events WHERE transaction_id IS NOT NULL "
                + "UNION ALL SELECT transaction_id FROM loan_payments WHERE transaction_id IS NOT NULL "
                + "UNION ALL SELECT transaction_id FROM loan_charges WHERE transaction_id IS NOT NULL "
                + "UNION ALL SELECT transaction_id FROM lendings WHERE transaction_id IS NOT NULL";

        List<?> results = entityManager.createNativeQuery(sql).getResultList();
        for (Object res : results) {
            if (res != null) {
                try {
                    ids.add(UUID.fromString(res.toString()));
                } catch (IllegalArgumentException ignored) {}
            }
        }
        return ids;
    }

    private Transaction loadOwned(UUID transactionId) {
        Transaction transaction = transactionRepository.findById(transactionId)
                .orElseThrow(() -> new ValidationException("Transaction not found: " + transactionId));

        UUID currentUserId = UserContext.getCurrentUserId();
        if (transaction.getUser() == null || !transaction.getUser().getId().equals(currentUserId)) {
            log.warn("Security Breach Attempt: User {} tried to link foreign transaction {}", currentUserId, transactionId);
            throw new ValidationException("Transaction " + transactionId + " does not belong to the current user");
        }
        return transaction;
    }
}
