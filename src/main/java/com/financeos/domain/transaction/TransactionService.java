package com.financeos.domain.transaction;

import com.financeos.api.transaction.dto.CreateTransactionRequest;

import com.financeos.core.exception.ResourceNotFoundException;
import com.financeos.core.exception.ValidationException;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.category.Category;
import com.financeos.domain.category.CategoryService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

@Service
@Transactional
public class TransactionService {

    private final TransactionRepository transactionRepository;
    private final AccountRepository accountRepository;
    private final CategoryService categoryService;

    public TransactionService(TransactionRepository transactionRepository,
            AccountRepository accountRepository,
            CategoryService categoryService) {
        this.transactionRepository = transactionRepository;
        this.accountRepository = accountRepository;
        this.categoryService = categoryService;
    }

    public Transaction createTransaction(CreateTransactionRequest request) {
        // Validate non-zero amount
        if (request.amount().compareTo(BigDecimal.ZERO) == 0) {
            throw new ValidationException("Transaction amount cannot be zero");
        }

        Account account = accountRepository.findById(request.accountId())
                .orElseThrow(() -> new ResourceNotFoundException("Account", request.accountId()));

        // Convert signed amount to unsigned + type
        BigDecimal absoluteAmount = request.amount().abs();
        TransactionType type = request.amount()
                .compareTo(BigDecimal.ZERO) < 0
                        ? TransactionType.DEBIT
                        : TransactionType.CREDIT;

        Transaction transaction = new Transaction(
                account,
                request.date(),
                absoluteAmount, // Store unsigned
                request.description(),
                request.source(),
                type); // Store type

        transaction.setUser(account.getUser());
        transaction.setMetadata(request.metadata());

        // Set monitoring flags (default to false if not provided)
        transaction.setIsTransactionExcluded(
                request.isTransactionExcluded() != null ? request.isTransactionExcluded() : false);
        transaction.setIsTransactionUnderMonitoring(
                request.isTransactionUnderMonitoring() != null ? request.isTransactionUnderMonitoring() : false);

        // Associate categories if provided
        if (request.categoryIds() != null && !request.categoryIds().isEmpty()) {
            List<Category> categories = categoryService.findCategoriesByIds(request.categoryIds());
            if (categories.size() != request.categoryIds().size()) {
                throw new ValidationException("One or more category IDs are invalid");
            }
            transaction.getCategories().addAll(categories);
        }

        return transactionRepository.save(transaction);
    }

    @Transactional(readOnly = true)
    public Page<Transaction> getAllTransactions(Pageable pageable) {
        return transactionRepository.findAllOrdered(pageable);
    }
}
