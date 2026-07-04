package com.financeos.domain.transaction;

import com.financeos.api.transaction.dto.CreateTransactionRequest;
import com.financeos.api.transaction.dto.TransactionSearchRequest;

import com.financeos.core.exception.ResourceNotFoundException;
import com.financeos.core.exception.ValidationException;
import com.financeos.core.security.UserContext;
import com.financeos.domain.user.User;
import com.financeos.domain.user.UserRepository;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.category.Category;
import com.financeos.domain.category.CategoryRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@Transactional
@Slf4j
public class TransactionService {

    private final TransactionRepository transactionRepository;
    private final AccountRepository accountRepository;
    private final CategoryRepository categoryRepository;
    private final UserRepository userRepository;

    public TransactionService(TransactionRepository transactionRepository,
            AccountRepository accountRepository,
            CategoryRepository categoryRepository,
            UserRepository userRepository) {
        this.transactionRepository = transactionRepository;
        this.accountRepository = accountRepository;
        this.categoryRepository = categoryRepository;
        this.userRepository = userRepository;
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
        TransactionType type = request.amount().compareTo(BigDecimal.ZERO) >= 0 ? TransactionType.CREDIT
                : TransactionType.DEBIT;

        Transaction transaction = new Transaction(
                account,
                request.date(),
                absoluteAmount, // Store unsigned
                request.description(),
                TransactionSource.manual, // Corrected to lowercase
                type, // Store type
                request.isTransactionUnderMonitoring() != null && request.isTransactionUnderMonitoring(),
                request.isTransactionExcluded() != null && request.isTransactionExcluded());

        // SECURITY: Enforce session-based identity.
        // We do NOT trust the account owner alone; we use the current session user.
        UUID currentSessionUserId = com.financeos.core.security.UserContext.getCurrentUserId();

        // SECURITY: Verify that the account actually belongs to the session user.
        if (!account.getUser().getId().equals(currentSessionUserId)) {
            log.error("Security Breach Attempt: User {} tried to add transaction to Account {} owned by User {}",
                    currentSessionUserId, account.getId(), account.getUser().getId());
            throw new ValidationException("You do not have permission to add transactions to this account.");
        }

        User currentUser = userRepository.getReferenceById(currentSessionUserId);
        transaction.setUser(currentUser);

        // Restore Categories Relationship
        if (request.categoryIds() != null && !request.categoryIds().isEmpty()) {
            java.util.List<Category> foundCategories = categoryRepository.findAllById(request.categoryIds());
            if (foundCategories.size() != request.categoryIds().size()) {
                throw new com.financeos.core.exception.ResourceNotFoundException("One or more categories not found");
            }
            transaction.setCategories(new java.util.HashSet<>(foundCategories));
        }

        return transactionRepository.save(transaction);
    }

    private Page<Transaction> queryTransactions(TransactionSearchCriteria criteria, Pageable pageable) {
        UUID userId = com.financeos.core.security.UserContext.getCurrentUserId();
        log.debug("Fetching transactions with running balance for user session: {}", userId);

        // 1. Fetch IDs and running balances with pagination
        Page<TransactionRepository.TransactionBalanceProjection> idPage = transactionRepository
                .findFiltered(userId, criteria, pageable);

        if (idPage.isEmpty()) {
            return Page.empty(pageable);
        }

        List<UUID> ids = idPage.getContent().stream()
                .map(TransactionRepository.TransactionBalanceProjection::getId)
                .toList();

        // 2. Fetch full entities with associations
        List<Transaction> transactions = transactionRepository.findAllByIdIn(ids);

        // 3. Map balances and restore order
        Map<UUID, java.math.BigDecimal> balanceMap = idPage.getContent().stream()
                .collect(Collectors.toMap(
                        TransactionRepository.TransactionBalanceProjection::getId,
                        TransactionRepository.TransactionBalanceProjection::getBalance));

        List<Transaction> orderedTransactions = ids.stream()
                .map(id -> {
                    Transaction t = transactions.stream()
                            .filter(tx -> tx.getId().equals(id))
                            .findFirst()
                            .orElseThrow(() -> new IllegalStateException("Transaction not found for ID: " + id));
                    t.setBalance(balanceMap.get(id));
                    return t;
                })
                .toList();

        return new org.springframework.data.domain.PageImpl<>(
                orderedTransactions,
                pageable,
                idPage.getTotalElements());
    }

    @Transactional(readOnly = true)
    public Page<Transaction> getAllTransactions(Pageable pageable) {
        return queryTransactions(new TransactionSearchCriteria(List.of(), null), pageable);
    }

    @Transactional(readOnly = true)
    public Page<Transaction> searchTransactions(TransactionSearchRequest request, Pageable pageable) {
        TransactionSearchCriteria criteria = new TransactionSearchCriteria(
                request.filters() != null ? request.filters() : List.of(),
                request.search());
        return queryTransactions(criteria, pageable);
    }

    public Transaction updateTransaction(UUID id, com.financeos.api.transaction.dto.UpdateTransactionRequest request) {
        Transaction transaction = transactionRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Transaction", id));

        // SECURITY: Verify ownership
        UUID currentSessionUserId = com.financeos.core.security.UserContext.getCurrentUserId();
        if (!transaction.getUser().getId().equals(currentSessionUserId)) {
            log.error("Security Breach Attempt: User {} tried to update Transaction {} owned by User {}",
                    currentSessionUserId, id, transaction.getUser().getId());
            throw new ValidationException("You do not have permission to update this transaction.");
        }

        // Validate non-zero amount
        if (request.amount().compareTo(BigDecimal.ZERO) == 0) {
            throw new ValidationException("Transaction amount cannot be zero");
        }

        // Update fields
        transaction.setDate(request.date());
        transaction.setDescription(request.description());

        // Handle flags
        if (request.isTransactionUnderMonitoring() != null) {
            transaction.setTransactionUnderMonitoring(request.isTransactionUnderMonitoring());
        }
        if (request.isTransactionExcluded() != null) {
            transaction.setTransactionExcluded(request.isTransactionExcluded());
        }
        if (request.reviewType() != null) {
            transaction.setReviewType(request.reviewType());
        }

        // Handle amount and type
        BigDecimal absoluteAmount = request.amount().abs();
        TransactionType type = request.amount().compareTo(BigDecimal.ZERO) >= 0 ? TransactionType.CREDIT
                : TransactionType.DEBIT;
        transaction.setAmount(absoluteAmount);
        transaction.setType(type);

        // Update Categories (Clears if null)
        if (request.categoryIds() != null) {
            java.util.List<Category> foundCategories = categoryRepository.findAllById(request.categoryIds());
            if (foundCategories.size() != request.categoryIds().size()) {
                throw new com.financeos.core.exception.ResourceNotFoundException("One or more categories not found");
            }
            transaction.setCategories(new java.util.HashSet<>(foundCategories));
        } else {
            transaction.setCategories(new java.util.HashSet<>());
        }

        return transactionRepository.save(transaction);
    }

    public void deleteTransaction(UUID id) {
        Transaction transaction = transactionRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Transaction", id));

        // SECURITY: Verify ownership
        UUID currentSessionUserId = com.financeos.core.security.UserContext.getCurrentUserId();
        if (!transaction.getUser().getId().equals(currentSessionUserId)) {
            log.error("Security Breach Attempt: User {} tried to delete Transaction {} owned by User {}",
                    currentSessionUserId, id, transaction.getUser().getId());
            throw new ValidationException("You do not have permission to delete this transaction.");
        }

        transactionRepository.delete(transaction);
    }

    public int batchReview(List<UUID> transactionIds, ReviewType reviewType) {
        List<Transaction> transactions = loadOwnedTransactions(transactionIds, "batch-review");
        for (Transaction transaction : transactions) {
            transaction.setReviewType(reviewType);
        }
        transactionRepository.saveAll(transactions);
        return transactions.size();
    }

    public int batchDelete(List<UUID> transactionIds) {
        List<Transaction> transactions = loadOwnedTransactions(transactionIds, "batch-delete");
        transactionRepository.deleteAll(transactions);
        return transactions.size();
    }

    private List<Transaction> loadOwnedTransactions(List<UUID> transactionIds, String action) {
        Set<UUID> ids = new LinkedHashSet<>(transactionIds);
        if (ids.size() > 500) {
            throw new ValidationException("Transaction IDs batch cannot exceed 500");
        }

        UUID currentSessionUserId = UserContext.getCurrentUserId();
        Map<UUID, Transaction> transactionsById = transactionRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(Transaction::getId, t -> t));

        List<UUID> offendingIds = new ArrayList<>();
        List<Transaction> owned = new ArrayList<>(ids.size());
        for (UUID id : ids) {
            Transaction transaction = transactionsById.get(id);
            if (transaction == null || transaction.getUser() == null
                    || !transaction.getUser().getId().equals(currentSessionUserId)) {
                offendingIds.add(id);
            } else {
                owned.add(transaction);
            }
        }

        if (!offendingIds.isEmpty()) {
            log.error("Security Breach Attempt: User {} attempted {} on transactions they do not own or that do not exist. Offending IDs: {}",
                    currentSessionUserId, action, offendingIds);
            throw new ValidationException("You do not have permission to modify these transactions.",
                    Map.of("offendingIds", offendingIds.stream().map(UUID::toString).collect(Collectors.joining(","))));
        }
        return owned;
    }
}
