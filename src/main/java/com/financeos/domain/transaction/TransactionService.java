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
import com.financeos.domain.categorization.CategorizationService;
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
    private final ReviewStatusManager reviewStatusManager;
    private final CategorizationService categorizationService;
    private final com.financeos.domain.transaction.link.TransactionLinkService transactionLinkService;
    private final TransactionService self;

    @org.springframework.beans.factory.annotation.Autowired
    public TransactionService(TransactionRepository transactionRepository,
            AccountRepository accountRepository,
            CategoryRepository categoryRepository,
            UserRepository userRepository,
            ReviewStatusManager reviewStatusManager,
            CategorizationService categorizationService,
            @org.springframework.context.annotation.Lazy com.financeos.domain.transaction.link.TransactionLinkService transactionLinkService,
            @org.springframework.context.annotation.Lazy TransactionService self) {
        this.transactionRepository = transactionRepository;
        this.accountRepository = accountRepository;
        this.categoryRepository = categoryRepository;
        this.userRepository = userRepository;
        this.reviewStatusManager = reviewStatusManager;
        this.categorizationService = categorizationService;
        this.transactionLinkService = transactionLinkService;
        this.self = self != null ? self : this;
    }

    public TransactionService(TransactionRepository transactionRepository,
            AccountRepository accountRepository,
            CategoryRepository categoryRepository,
            UserRepository userRepository,
            ReviewStatusManager reviewStatusManager,
            CategorizationService categorizationService,
            TransactionService self) {
        this(transactionRepository, accountRepository, categoryRepository, userRepository, reviewStatusManager, categorizationService, null, self);
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

        if (transaction.isTransactionUnderMonitoring()) {
            transaction.setMonitoringReason(request.monitoringReason());
        } else {
            transaction.setMonitoringReason(null);
        }
        transaction.setMcc(request.mcc());

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

        reviewStatusManager.transitionTo(transaction, ReviewType.NA);

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

        if (request.isTransactionUnderMonitoring() != null) {
            transaction.setTransactionUnderMonitoring(request.isTransactionUnderMonitoring());
        }
        if (transaction.isTransactionUnderMonitoring()) {
            transaction.setMonitoringReason(request.monitoringReason());
        } else {
            transaction.setMonitoringReason(null);
        }
        if (request.isTransactionExcluded() != null) {
            transaction.setTransactionExcluded(request.isTransactionExcluded());
        }
        if (request.mcc() != null) {
            transaction.setMcc(request.mcc().isBlank() ? null : request.mcc());
        }
        // Capture the stored review status before any mutation so we can tell an
        // explicit status change apart from the client merely echoing the current value.
        ReviewType originalReviewType = transaction.getReviewType();

        // Feedback loop
        boolean categoriesEqual = false;
        if (request.categoryIds() != null) {
            Set<UUID> currentCategoryIds = transaction.getCategories().stream()
                    .map(tc -> tc.getCategory().getId())
                    .collect(Collectors.toSet());
            Set<UUID> requestCatIds = new java.util.HashSet<>(request.categoryIds());
            categoriesEqual = currentCategoryIds.equals(requestCatIds);
        }

        // Only clear CATEGORY_UNVERIFIED when the categories actually changed.
        // The client round-trips the existing categoryIds on every edit, so gating
        // on presence alone would wrongly clear the reason when unrelated fields
        // (e.g. monitoring flag) are edited, then fail to re-apply NEEDS_REVIEW.
        if (request.categoryIds() != null && !categoriesEqual) {
            reviewStatusManager.clearReason(transaction, ReviewReason.CATEGORY_UNVERIFIED, ReviewType.MANUALLY_REVIEWED);
        }

        if (transaction.getAppliedRule() != null && request.categoryIds() != null) {
            if (categoriesEqual) {
                categorizationService.verifyRule(transaction.getAppliedRule());
            } else {
                transaction.setAppliedRule(null);
            }
        }

        // Only drive a transition when the caller actually asks for a *different* status.
        // The edit form always echoes the transaction's current reviewType, so re-applying
        // it unconditionally could try to force NEEDS_REVIEW after clearReason already
        // promoted the txn (emptying its reasons), which throws. A field edit must not
        // move review status on its own.
        if (request.reviewType() != null && request.reviewType() != originalReviewType) {
            reviewStatusManager.transitionTo(transaction, request.reviewType());
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

        if (transactionLinkService != null) {
            transactionLinkService.autoDissolveLinksForDeletedTransactions(List.of(id));
        }
        transactionRepository.delete(transaction);
    }

    @Transactional
    public com.financeos.api.transaction.dto.BatchReviewResponse batchReview(List<UUID> transactionIds, ReviewType reviewType, List<ReviewReason> reviewReasons) {
        if (transactionIds == null || transactionIds.isEmpty()) {
            return new com.financeos.api.transaction.dto.BatchReviewResponse(List.of(), List.of(), List.of());
        }
        if (transactionIds.size() > 500) {
            throw new ValidationException("Transaction IDs batch cannot exceed 500");
        }

        // Validate reasons for clearing types
        if (reviewType == ReviewType.MANUALLY_REVIEWED || reviewType == ReviewType.AUTO_REVIEWED || reviewType == ReviewType.NA) {
            if (reviewReasons == null || reviewReasons.isEmpty()) {
                throw new ValidationException("Review reasons must not be empty when transitioning to a cleared review state.");
            }
        }

        UUID currentSessionUserId = UserContext.getCurrentUserId();
        List<Transaction> fetched = transactionRepository.findAllByIdIn(transactionIds);
        Map<UUID, Transaction> map = fetched.stream()
                .collect(Collectors.toMap(Transaction::getId, t -> t, (a, b) -> a));

        List<String> succeededIds = new ArrayList<>();
        List<String> skippedIds = new ArrayList<>();
        List<com.financeos.api.transaction.dto.BatchFailure> failures = new ArrayList<>();
        List<Transaction> toSave = new ArrayList<>();

        for (UUID id : transactionIds) {
            Transaction transaction = map.get(id);
            if (transaction == null) {
                failures.add(new com.financeos.api.transaction.dto.BatchFailure(id.toString(), "NOT_FOUND"));
                continue;
            }

            if (transaction.getUser() == null || !transaction.getUser().getId().equals(currentSessionUserId)) {
                failures.add(new com.financeos.api.transaction.dto.BatchFailure(id.toString(), "NOT_OWNED"));
                continue;
            }

            if (reviewType == ReviewType.MANUALLY_REVIEWED || reviewType == ReviewType.AUTO_REVIEWED || reviewType == ReviewType.NA) {
                boolean hasAny = false;
                if (transaction.getReviewReasons() != null) {
                    for (ReviewReason r : reviewReasons) {
                        if (transaction.getReviewReasons().contains(r)) {
                            hasAny = true;
                            break;
                        }
                    }
                }
                if (!hasAny) {
                    skippedIds.add(id.toString());
                    continue;
                }

                for (ReviewReason r : reviewReasons) {
                    reviewStatusManager.clearReason(transaction, r, reviewType);
                }

                if (transaction.getReviewType() == ReviewType.MANUALLY_REVIEWED) {
                    if (transaction.getAppliedRule() != null && !transaction.getAppliedRule().isVerified()) {
                        categorizationService.verifyRule(transaction.getAppliedRule());
                    }
                }
            } else {
                reviewStatusManager.transitionTo(transaction, reviewType);
            }

            toSave.add(transaction);
            succeededIds.add(id.toString());
        }

        if (!toSave.isEmpty()) {
            transactionRepository.saveAll(toSave);
        }

        return new com.financeos.api.transaction.dto.BatchReviewResponse(succeededIds, skippedIds, failures);
    }

    @Transactional
    public com.financeos.api.transaction.dto.BatchDeleteResponse batchDelete(List<UUID> transactionIds) {
        if (transactionIds == null || transactionIds.isEmpty()) {
            return new com.financeos.api.transaction.dto.BatchDeleteResponse(List.of(), List.of());
        }
        if (transactionIds.size() > 500) {
            throw new ValidationException("Transaction IDs batch cannot exceed 500");
        }

        UUID currentSessionUserId = UserContext.getCurrentUserId();
        List<Transaction> fetched = transactionRepository.findAllByIdIn(transactionIds);
        Map<UUID, Transaction> map = fetched.stream()
                .collect(Collectors.toMap(Transaction::getId, t -> t, (a, b) -> a));

        List<String> succeededIds = new ArrayList<>();
        List<com.financeos.api.transaction.dto.BatchFailure> failures = new ArrayList<>();
        List<UUID> toDeleteIds = new ArrayList<>();

        for (UUID id : transactionIds) {
            Transaction transaction = map.get(id);
            if (transaction == null) {
                failures.add(new com.financeos.api.transaction.dto.BatchFailure(id.toString(), "NOT_FOUND"));
            } else if (transaction.getUser() == null || !transaction.getUser().getId().equals(currentSessionUserId)) {
                failures.add(new com.financeos.api.transaction.dto.BatchFailure(id.toString(), "NOT_OWNED"));
            } else {
                toDeleteIds.add(id);
                succeededIds.add(id.toString());
            }
        }

        if (!toDeleteIds.isEmpty()) {
            if (transactionLinkService != null) {
                transactionLinkService.autoDissolveLinksForDeletedTransactions(toDeleteIds);
            }
            transactionRepository.deleteAllByIdInBatch(toDeleteIds);
        }

        return new com.financeos.api.transaction.dto.BatchDeleteResponse(succeededIds, failures);
    }


}
