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

import com.financeos.api.transaction.dto.MergeTransactionsResponse;
import com.financeos.domain.transaction.link.TransactionLink;
import com.financeos.domain.transaction.link.TransactionLinkMember;
import com.financeos.domain.transaction.link.TransactionLinkRepository;
import com.financeos.domain.statement.StatementTransaction;
import com.financeos.domain.statement.StatementTransactionId;
import com.financeos.domain.statement.StatementTransactionRepository;
import java.util.Optional;

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
    private final TransactionLinkRepository transactionLinkRepository;
    private final StatementTransactionRepository statementTransactionRepository;
    private final com.financeos.core.observability.AuditLogger auditLogger;
    private final com.financeos.domain.account.card.AccountCardRepository cardRepository;
    private final TransactionService self;

    @org.springframework.beans.factory.annotation.Autowired
    public TransactionService(TransactionRepository transactionRepository,
            AccountRepository accountRepository,
            CategoryRepository categoryRepository,
            UserRepository userRepository,
            ReviewStatusManager reviewStatusManager,
            CategorizationService categorizationService,
            @org.springframework.context.annotation.Lazy com.financeos.domain.transaction.link.TransactionLinkService transactionLinkService,
            @org.springframework.context.annotation.Lazy TransactionLinkRepository transactionLinkRepository,
            @org.springframework.context.annotation.Lazy StatementTransactionRepository statementTransactionRepository,
            com.financeos.core.observability.AuditLogger auditLogger,
            com.financeos.domain.account.card.AccountCardRepository cardRepository,
            @org.springframework.context.annotation.Lazy TransactionService self) {
        this.transactionRepository = transactionRepository;
        this.accountRepository = accountRepository;
        this.categoryRepository = categoryRepository;
        this.userRepository = userRepository;
        this.reviewStatusManager = reviewStatusManager;
        this.categorizationService = categorizationService;
        this.transactionLinkService = transactionLinkService;
        this.transactionLinkRepository = transactionLinkRepository;
        this.statementTransactionRepository = statementTransactionRepository;
        this.auditLogger = auditLogger;
        this.cardRepository = cardRepository;
        this.self = self != null ? self : this;
    }

    public TransactionService(TransactionRepository transactionRepository,
            AccountRepository accountRepository,
            CategoryRepository categoryRepository,
            UserRepository userRepository,
            ReviewStatusManager reviewStatusManager,
            CategorizationService categorizationService,
            TransactionService self) {
        this(transactionRepository, accountRepository, categoryRepository, userRepository, reviewStatusManager, categorizationService, null, null, null, null, null, self);
    }

    public TransactionService(TransactionRepository transactionRepository,
            AccountRepository accountRepository,
            CategoryRepository categoryRepository,
            UserRepository userRepository,
            ReviewStatusManager reviewStatusManager,
            CategorizationService categorizationService,
            com.financeos.domain.transaction.link.TransactionLinkService transactionLinkService,
            TransactionLinkRepository transactionLinkRepository,
            StatementTransactionRepository statementTransactionRepository,
            com.financeos.core.observability.AuditLogger auditLogger,
            TransactionService self) {
        this(transactionRepository, accountRepository, categoryRepository, userRepository, reviewStatusManager, categorizationService, transactionLinkService, transactionLinkRepository, statementTransactionRepository, auditLogger, null, self);
    }


    /**
     * Overwrite semantics: a present rewardDetails object applies all six fields
     * (nulls clear); an absent one leaves stored values untouched, so callers that
     * don't know about reward details can't wipe them.
     */
    private static void applyRewardDetails(Transaction transaction,
            com.financeos.api.transaction.dto.RewardDetailsRequest details) {
        if (details == null) {
            return;
        }
        transaction.setSettlementDate(details.settlementDate());
        transaction.setInstantDiscount(details.instantDiscount());
        transaction.setConvenienceFee(details.convenienceFee());
        transaction.setChannel(details.channel());
        transaction.setIsEmi(details.isEmi());
        transaction.setIsInternational(details.isInternational());
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
        applyRewardDetails(transaction, request.rewardDetails());

        if (request.cardId() != null) {
            com.financeos.domain.account.card.AccountCard card = cardRepository.findById(request.cardId())
                    .orElseThrow(() -> new ResourceNotFoundException("AccountCard", request.cardId()));
            if (!card.getAccount().getId().equals(account.getId())) {
                throw new ValidationException("Card does not belong to the transaction's account");
            }
            transaction.setCard(card);
        }

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

        Transaction savedTxn = transactionRepository.save(transaction);
        auditLogger.mutation("Transaction", savedTxn.getId(), "CREATE", "user:" + currentSessionUserId, "manual",
                List.of("amount", "description", "account", "date"), null, savedTxn.getAmount(), "INR");

        return savedTxn;
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

        BigDecimal amountBefore = transaction.getAmount();

        if (request.accountId() != null && !request.accountId().equals(transaction.getAccount().getId())) {
            Account newAccount = accountRepository.findById(request.accountId())
                    .orElseThrow(() -> new ResourceNotFoundException("Account", request.accountId()));
            if (!newAccount.getUser().getId().equals(currentSessionUserId)) {
                throw new ValidationException("You do not have permission to move transaction to this account.");
            }
            transaction.setAccount(newAccount);
            if (transaction.getCard() != null && !transaction.getCard().getAccount().getId().equals(newAccount.getId())) {
                transaction.setCard(null);
            }
        }

        if (request.cardId() != null) {
            com.financeos.domain.account.card.AccountCard card = cardRepository.findById(request.cardId())
                    .orElseThrow(() -> new ResourceNotFoundException("AccountCard", request.cardId()));
            if (!card.getAccount().getId().equals(transaction.getAccount().getId())) {
                throw new ValidationException("Card does not belong to the transaction's account");
            }
            transaction.setCard(card);
        } else {
            transaction.setCard(null);
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
        applyRewardDetails(transaction, request.rewardDetails());
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

        Transaction saved = transactionRepository.save(transaction);
        if (auditLogger != null) {
            auditLogger.mutation("Transaction", saved.getId(), "UPDATE", "user:" + currentSessionUserId, "manual",
                    List.of("amount", "description", "date", "categories"), amountBefore, saved.getAmount(), "INR");
        }
        org.hibernate.Hibernate.initialize(saved.getReviewReasons());
        return saved;
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
        if (auditLogger != null) {
            auditLogger.mutation("Transaction", id, "DELETE", "user:" + currentSessionUserId, "manual",
                    List.of("amount", "description"), transaction.getAmount(), null, "INR");
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

    @Transactional
    public MergeTransactionsResponse mergeTransactions(UUID keepId, UUID deleteId) {
        if (keepId == null || deleteId == null) {
            throw new ValidationException("keepId and deleteId must not be null");
        }
        if (keepId.equals(deleteId)) {
            throw new ValidationException("Cannot merge a transaction with itself");
        }

        Transaction kept = transactionRepository.findById(keepId)
                .orElseThrow(() -> new ResourceNotFoundException("Transaction", keepId));
        Transaction deleted = transactionRepository.findById(deleteId)
                .orElseThrow(() -> new ResourceNotFoundException("Transaction", deleteId));

        UUID currentSessionUserId = UserContext.getCurrentUserId();
        if (kept.getUser() == null || !kept.getUser().getId().equals(currentSessionUserId)
                || deleted.getUser() == null || !deleted.getUser().getId().equals(currentSessionUserId)) {
            log.error("Security Breach Attempt: User {} tried to merge transactions {} and {}",
                    currentSessionUserId, keepId, deleteId);
            throw new ValidationException("You do not have permission to merge these transactions.");
        }

        if (!kept.getAccount().getId().equals(deleted.getAccount().getId())) {
            throw new ValidationException("Cannot merge transactions from different accounts.");
        }

        // --- CARRY-OVER ---
        if (kept.getCategories().isEmpty() && !deleted.getCategories().isEmpty()) {
            Set<Category> deletedCats = deleted.getCategories().stream()
                    .map(TransactionCategory::getCategory)
                    .collect(Collectors.toSet());
            kept.setCategories(deletedCats);

            if (kept.getAppliedRule() == null && deleted.getAppliedRule() != null) {
                kept.setAppliedRule(deleted.getAppliedRule());
            }
        }

        if (kept.getChannel() == null && deleted.getChannel() != null) {
            kept.setChannel(deleted.getChannel());
        }
        if ((kept.getIsEmi() == null || !kept.getIsEmi()) && Boolean.TRUE.equals(deleted.getIsEmi())) {
            kept.setIsEmi(true);
        }
        if ((kept.getIsInternational() == null || !kept.getIsInternational()) && Boolean.TRUE.equals(deleted.getIsInternational())) {
            kept.setIsInternational(true);
        }
        if (kept.getConvenienceFee() == null && deleted.getConvenienceFee() != null) {
            kept.setConvenienceFee(deleted.getConvenienceFee());
        }
        if (kept.getInstantDiscount() == null && deleted.getInstantDiscount() != null) {
            kept.setInstantDiscount(deleted.getInstantDiscount());
        }
        if (kept.getSettlementDate() == null && deleted.getSettlementDate() != null) {
            kept.setSettlementDate(deleted.getSettlementDate());
        }
        if (deleted.isTransactionUnderMonitoring()) {
            kept.setTransactionUnderMonitoring(true);
        }
        if ((kept.getMonitoringReason() == null || kept.getMonitoringReason().isBlank())
                && deleted.getMonitoringReason() != null && !deleted.getMonitoringReason().isBlank()) {
            kept.setMonitoringReason(deleted.getMonitoringReason());
        }
        if ((kept.getDescription() == null || kept.getDescription().isBlank())
                && deleted.getDescription() != null && !deleted.getDescription().isBlank()) {
            kept.setDescription(deleted.getDescription());
        }
        if ((kept.getSourcedDescription() == null || kept.getSourcedDescription().isBlank())
                && deleted.getSourcedDescription() != null && !deleted.getSourcedDescription().isBlank()) {
            kept.setSourcedDescription(deleted.getSourcedDescription());
        }
        if (kept.getMcc() == null && deleted.getMcc() != null) {
            kept.setMcc(deleted.getMcc());
        }
        if (kept.getCard() == null && deleted.getCard() != null) {
            kept.setCard(deleted.getCard());
        }

        // --- TRANSACTION LINKS RE-POINTING ---
        if (transactionLinkRepository != null) {
            List<UUID> affectedLinkIds = transactionLinkRepository.findLinkIdsByMemberTransactionIds(List.of(deleteId));
            if (!affectedLinkIds.isEmpty()) {
                List<TransactionLink> affectedLinks = transactionLinkRepository.findWithMembersByIdIn(affectedLinkIds);
                for (TransactionLink link : affectedLinks) {
                    boolean keptIsMember = link.getMembers().stream()
                            .anyMatch(m -> m.getTransaction().getId().equals(keepId));

                    Optional<TransactionLinkMember> deletedMemberOpt = link.getMembers().stream()
                            .filter(m -> m.getTransaction().getId().equals(deleteId))
                            .findFirst();

                    boolean deletedWasAnchor = deletedMemberOpt.map(TransactionLinkMember::isAnchor).orElse(false);

                    link.getMembers().removeIf(m -> m.getTransaction().getId().equals(deleteId));

                    if (keptIsMember) {
                        dissolveOrSaveLink(link);
                    } else {
                        Optional<TransactionLink> existingKeptLink = transactionLinkRepository.findByMembers_Transaction_Id(keepId);
                        if (existingKeptLink.isPresent()) {
                            dissolveOrSaveLink(link);
                        } else {
                            TransactionLinkMember newMember = new TransactionLinkMember(link, kept, deletedWasAnchor);
                            link.getMembers().add(newMember);
                            transactionLinkRepository.save(link);
                        }
                    }
                }
            }
        }

        // --- STATEMENT TRANSACTIONS RE-POINTING ---
        if (statementTransactionRepository != null) {
            List<StatementTransaction> deletedStRows = statementTransactionRepository.findByIdTransactionId(deleteId);
            for (StatementTransaction st : deletedStRows) {
                UUID stmtId = st.getId().getStatementId();
                statementTransactionRepository.delete(st);
                statementTransactionRepository.flush();

                boolean keptAlreadyHasRow = statementTransactionRepository
                        .existsById(new StatementTransactionId(stmtId, keepId));
                if (!keptAlreadyHasRow) {
                    StatementTransaction newSt = new StatementTransaction(
                            stmtId, keepId, st.getLineIndex(), st.getBalanceAfter(), st.getChainValid());
                    statementTransactionRepository.save(newSt);
                }
            }
        }

        // --- HARD-DELETE DELETED TRANSACTION ---
        if (auditLogger != null) {
            auditLogger.mutation("Transaction", deleteId, "DELETE_MERGED", "user:" + currentSessionUserId, "manual",
                    List.of("mergedInto:" + keepId), deleted.getAmount(), null, "INR");
        }
        transactionRepository.delete(deleted);

        // --- REVIEW REASONS CLEARING ON KEPT ---
        // Only touch review state when there is actually something to clear, so a kept
        // transaction that is already NA/AUTO_REVIEWED is not re-stamped MANUALLY_REVIEWED.
        boolean hasClearableReason = kept.getReviewReasons() != null
                && (kept.getReviewReasons().contains(ReviewReason.UNRECONCILED)
                        || kept.getReviewReasons().contains(ReviewReason.DUPLICATE_SUSPECT));
        if (hasClearableReason) {
            reviewStatusManager.clearReason(kept, ReviewReason.UNRECONCILED, ReviewType.MANUALLY_REVIEWED);
            reviewStatusManager.clearReason(kept, ReviewReason.DUPLICATE_SUSPECT, ReviewType.MANUALLY_REVIEWED);
        }

        if (kept.getReviewType() == ReviewType.MANUALLY_REVIEWED) {
            if (kept.getAppliedRule() != null && !kept.getAppliedRule().isVerified()) {
                categorizationService.verifyRule(kept.getAppliedRule());
            }
        }

        Transaction savedKept = transactionRepository.save(kept);
        org.hibernate.Hibernate.initialize(savedKept.getReviewReasons());

        return new MergeTransactionsResponse(savedKept.getId(), savedKept.getReviewType(), savedKept.getReviewReasons());
    }

    private void dissolveOrSaveLink(TransactionLink link) {
        List<TransactionLinkMember> remainingMembers = new ArrayList<>(link.getMembers());
        boolean hasAnchor = remainingMembers.stream().anyMatch(TransactionLinkMember::isAnchor);
        long counterpartCount = remainingMembers.stream().filter(m -> !m.isAnchor()).count();
        int remainingCount = remainingMembers.size();

        boolean shouldDissolve = (remainingCount < 2) || !hasAnchor || (counterpartCount < 1);
        if (shouldDissolve) {
            transactionLinkRepository.delete(link);
        } else {
            transactionLinkRepository.save(link);
        }
    }

    @Transactional
    public com.financeos.api.transaction.dto.BulkReattributeResponse bulkReattributeCard(
            com.financeos.api.transaction.dto.BulkReattributeCardRequest request) {
        UUID currentSessionUserId = com.financeos.core.security.UserContext.getCurrentUserId();
        Account account = accountRepository.findById(request.accountId())
                .orElseThrow(() -> new ResourceNotFoundException("Account", request.accountId()));

        if (!account.getUser().getId().equals(currentSessionUserId)) {
            throw new ValidationException("You do not have permission to modify this account's transactions.");
        }

        com.financeos.domain.account.card.AccountCard targetCard = null;
        if (request.cardId() != null) {
            targetCard = cardRepository.findById(request.cardId())
                    .orElseThrow(() -> new ResourceNotFoundException("AccountCard", request.cardId()));
            if (!targetCard.getAccount().getId().equals(account.getId())) {
                throw new ValidationException("Target card does not belong to the specified account.");
            }
        }

        if (request.currentCardId() != null) {
            com.financeos.domain.account.card.AccountCard currentCard = cardRepository.findById(request.currentCardId())
                    .orElseThrow(() -> new ResourceNotFoundException("AccountCard", request.currentCardId()));
            if (!currentCard.getAccount().getId().equals(account.getId())) {
                throw new ValidationException("Current card filter does not belong to the specified account.");
            }
        }

        List<Transaction> txns = transactionRepository.findForBulkReattribute(
                currentSessionUserId,
                account.getId(),
                request.from(),
                request.to(),
                request.currentCardId()
        );

        for (Transaction txn : txns) {
            txn.setCard(targetCard);
        }
        transactionRepository.saveAll(txns);

        return new com.financeos.api.transaction.dto.BulkReattributeResponse(txns.size());
    }
}

