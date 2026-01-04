package com.financeos.domain.transaction;

import com.financeos.api.transaction.dto.CreateTransactionRequest;
import com.financeos.core.exception.DuplicateResourceException;
import com.financeos.core.exception.ResourceNotFoundException;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

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
        // Generate hash for deduplication
        String originalHash = generateHash(request);

        if (transactionRepository.existsByOriginalHash(originalHash)) {
            throw new DuplicateResourceException("Transaction with same details already exists");
        }

        Account account = accountRepository.findById(request.accountId())
                .orElseThrow(() -> new ResourceNotFoundException("Account", request.accountId()));

        Transaction transaction = new Transaction(
                account,
                request.date(),
                request.amount(),
                request.description(),
                request.source(),
                originalHash);

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

    private String generateHash(CreateTransactionRequest request) {
        String data = String.format("%s|%s|%s|%s|%s",
                request.accountId(),
                request.date(),
                request.amount().toPlainString(),
                request.description(),
                request.source());

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}
