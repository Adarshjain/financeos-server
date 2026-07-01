package com.financeos.domain.ingestion;

import com.financeos.domain.transaction.Transaction;
import com.financeos.domain.transaction.TransactionRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Component
public class FileIngestionDbHandler {

    private final TransactionRepository transactionRepository;

    public FileIngestionDbHandler(TransactionRepository transactionRepository) {
        this.transactionRepository = transactionRepository;
    }

    @Transactional(readOnly = true)
    public List<Transaction> findExistingTransactions(UUID accountId, LocalDate minDate, LocalDate maxDate) {
        return transactionRepository.findByAccountIdAndDateRange(accountId, minDate, maxDate);
    }

    @Transactional
    public void saveTransactions(List<Transaction> newTxns, List<Transaction> dbTxnsToUpdate) {
        if (dbTxnsToUpdate != null && !dbTxnsToUpdate.isEmpty()) {
            transactionRepository.saveAll(dbTxnsToUpdate);
        }
        if (newTxns != null && !newTxns.isEmpty()) {
            transactionRepository.saveAll(newTxns);
        }
    }
}
