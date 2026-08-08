package com.financeos.domain.loan;

import com.financeos.core.exception.ValidationException;
import com.financeos.core.security.UserContext;
import com.financeos.domain.lending.LendingRepaymentRepository;
import com.financeos.domain.lending.LendingRepository;
import com.financeos.domain.transaction.Transaction;
import com.financeos.domain.transaction.TransactionRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Component
public class TransactionReferenceValidator {

    private static final Logger log = LoggerFactory.getLogger(TransactionReferenceValidator.class);

    private final TransactionRepository transactionRepository;
    private final LoanEventRepository loanEventRepository;
    private final LoanPaymentRepository loanPaymentRepository;
    private final LoanChargeRepository loanChargeRepository;
    private final LendingRepository lendingRepository;
    private final LendingRepaymentRepository lendingRepaymentRepository;

    @PersistenceContext
    private EntityManager entityManager;

    public TransactionReferenceValidator(
            TransactionRepository transactionRepository,
            LoanEventRepository loanEventRepository,
            LoanPaymentRepository loanPaymentRepository,
            LoanChargeRepository loanChargeRepository,
            LendingRepository lendingRepository,
            LendingRepaymentRepository lendingRepaymentRepository) {
        this.transactionRepository = transactionRepository;
        this.loanEventRepository = loanEventRepository;
        this.loanPaymentRepository = loanPaymentRepository;
        this.loanChargeRepository = loanChargeRepository;
        this.lendingRepository = lendingRepository;
        this.lendingRepaymentRepository = lendingRepaymentRepository;
    }

    public Transaction validateAndGetTransaction(UUID transactionId) {
        if (transactionId == null) {
            return null;
        }

        Transaction transaction = transactionRepository.findById(transactionId)
                .orElseThrow(() -> new ValidationException("Transaction not found: " + transactionId));

        UUID currentUserId = UserContext.getCurrentUserId();
        if (transaction.getUser() == null || !transaction.getUser().getId().equals(currentUserId)) {
            log.warn("Security Breach Attempt: User {} tried to link foreign transaction {}", currentUserId, transactionId);
            throw new ValidationException("Transaction " + transactionId + " does not belong to the current user");
        }

        if (isTransactionReferenced(transactionId)) {
            throw new ValidationException("Transaction " + transactionId + " is already linked to a loan or lending record");
        }

        return transaction;
    }

    public boolean isTransactionReferenced(UUID transactionId) {
        if (transactionId == null) {
            return false;
        }
        return loanEventRepository.existsByTransaction_Id(transactionId)
                || loanPaymentRepository.existsByTransaction_Id(transactionId)
                || loanChargeRepository.existsByTransaction_Id(transactionId)
                || lendingRepository.existsByTransaction_Id(transactionId)
                || lendingRepaymentRepository.existsByTransaction_Id(transactionId);
    }

    @SuppressWarnings("unchecked")
    public Set<UUID> getAllReferencedTransactionIds() {
        Set<UUID> ids = new HashSet<>();
        List<String> queries = List.of(
                "SELECT transaction_id FROM loan_events WHERE transaction_id IS NOT NULL",
                "SELECT transaction_id FROM loan_payments WHERE transaction_id IS NOT NULL",
                "SELECT transaction_id FROM loan_charges WHERE transaction_id IS NOT NULL",
                "SELECT transaction_id FROM lendings WHERE transaction_id IS NOT NULL",
                "SELECT transaction_id FROM lending_repayments WHERE transaction_id IS NOT NULL"
        );

        for (String sql : queries) {
            List<String> results = entityManager.createNativeQuery(sql).getResultList();
            for (String res : results) {
                if (res != null) {
                    try {
                        ids.add(UUID.fromString(res));
                    } catch (IllegalArgumentException ignored) {}
                }
            }
        }
        return ids;
    }
}
