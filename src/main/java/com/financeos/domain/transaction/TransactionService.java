package com.financeos.domain.transaction;

import com.financeos.api.transaction.dto.CreateTransactionRequest;

import com.financeos.core.exception.ResourceNotFoundException;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class TransactionService {

    private final TransactionRepository transactionRepository;
    private final AccountRepository accountRepository;

    public TransactionService(TransactionRepository transactionRepository,
            AccountRepository accountRepository) {
        this.transactionRepository = transactionRepository;
        this.accountRepository = accountRepository;
    }

    public Transaction createTransaction(CreateTransactionRequest request) {
        Account account = accountRepository.findById(request.accountId())
                .orElseThrow(() -> new ResourceNotFoundException("Account", request.accountId()));

        Transaction transaction = new Transaction(
                account,
                request.date(),
                request.amount(),
                request.description(),
                request.source());

        transaction.setUser(account.getUser());
        transaction.setCategory(request.category());
        transaction.setSubcategory(request.subcategory());
        transaction.setSpentFor(request.spentFor());
        transaction.setMetadata(request.metadata());

        return transactionRepository.save(transaction);
    }

    @Transactional(readOnly = true)
    public Page<Transaction> getAllTransactions(Pageable pageable) {
        return transactionRepository.findAllOrdered(pageable);
    }
}
