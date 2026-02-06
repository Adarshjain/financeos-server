package com.financeos.domain.transaction;

import com.financeos.api.transaction.dto.CreateTransactionRequest;

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

    @Transactional(readOnly = true)
    public Page<Transaction> getAllTransactions(Pageable pageable) {
        UUID userId = com.financeos.core.security.UserContext.getCurrentUserId();
        log.debug("Fetching transactions with running balance for user session: {}", userId);

        // Map sort properties and ensure stability with tie-breakers
        List<org.springframework.data.domain.Sort.Order> orders = new java.util.ArrayList<>();
        pageable.getSort().forEach(order -> {
            String property = order.getProperty();
            if (property.equalsIgnoreCase("date")) {
                orders.add(new org.springframework.data.domain.Sort.Order(order.getDirection(), "transaction_date"));
            } else if (property.equalsIgnoreCase("createdAt")) {
                orders.add(new org.springframework.data.domain.Sort.Order(order.getDirection(), "created_at"));
            } else if (!property.equalsIgnoreCase("balance")) { // Don't sort by the calculated balance field
                orders.add(order);
            }
        });

        // Mandatory tie-breakers for running balance stability
        if (orders.stream().noneMatch(o -> o.getProperty().equals("transaction_date"))) {
            orders.add(org.springframework.data.domain.Sort.Order.desc("transaction_date"));
        }
        if (orders.stream().noneMatch(o -> o.getProperty().equals("created_at"))) {
            orders.add(org.springframework.data.domain.Sort.Order.desc("created_at"));
        }
        if (orders.stream().noneMatch(o -> o.getProperty().equals("id"))) {
            orders.add(org.springframework.data.domain.Sort.Order.desc("id"));
        }

        Pageable nativePageable = org.springframework.data.domain.PageRequest.of(
                pageable.getPageNumber(),
                pageable.getPageSize(),
                org.springframework.data.domain.Sort.by(orders));

        log.debug("Native pagination sort: {}", nativePageable.getSort());

        // 1. Fetch IDs and running balances with pagination
        Page<TransactionRepository.TransactionBalanceProjection> idPage = transactionRepository
                .findIdsWithRunningBalance(userId.toString(), nativePageable);

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
        transaction.setSource(TransactionSource.manual); // Corrected to lowercase

        // Handle flags
        if (request.isTransactionUnderMonitoring() != null) {
            transaction.setTransactionUnderMonitoring(request.isTransactionUnderMonitoring());
        }
        if (request.isTransactionExcluded() != null) {
            transaction.setTransactionExcluded(request.isTransactionExcluded());
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
}
