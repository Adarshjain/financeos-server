package com.financeos.domain.ingestion;

import com.financeos.domain.transaction.Transaction;
import com.financeos.domain.transaction.TransactionRepository;
import com.financeos.domain.transaction.autolink.TransactionsIngestedEvent;
import org.hibernate.Hibernate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Component
public class FileIngestionDbHandler {

    private final TransactionRepository transactionRepository;

    /** Optional so tests that build the handler by hand need no publisher. */
    @Autowired(required = false)
    private ApplicationEventPublisher eventPublisher;

    public FileIngestionDbHandler(TransactionRepository transactionRepository) {
        this.transactionRepository = transactionRepository;
    }

    @Transactional(readOnly = true)
    public List<Transaction> findExistingTransactions(UUID accountId, LocalDate minDate, LocalDate maxDate) {
        List<Transaction> existing = transactionRepository.findByAccountIdAndDateRange(accountId, minDate, maxDate);
        // The duplicate pass runs outside this session and flags matches with DUPLICATE_SUSPECT,
        // which touches the lazy review-reasons collection; load it here or that flagging throws
        // LazyInitializationException and the whole upload fails.
        for (Transaction transaction : existing) {
            Hibernate.initialize(transaction.getReviewReasons());
        }
        return existing;
    }

    @Transactional
    public void saveTransactions(List<Transaction> newTxns, List<Transaction> dbTxnsToUpdate) {
        if (dbTxnsToUpdate != null && !dbTxnsToUpdate.isEmpty()) {
            transactionRepository.saveAll(dbTxnsToUpdate);
        }
        if (newTxns != null && !newTxns.isEmpty()) {
            transactionRepository.saveAll(newTxns);
            // Once per statement import; auto-linking runs after this transaction commits.
            if (eventPublisher != null && newTxns.get(0).getUser() != null) {
                eventPublisher.publishEvent(new TransactionsIngestedEvent(newTxns.get(0).getUser().getId()));
            }
        }
    }
}
