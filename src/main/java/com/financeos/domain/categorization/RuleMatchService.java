package com.financeos.domain.categorization;

import com.financeos.domain.category.Category;
import com.financeos.domain.transaction.ReviewReason;
import com.financeos.domain.transaction.ReviewStatusManager;
import com.financeos.domain.transaction.ReviewType;
import com.financeos.domain.transaction.Transaction;
import com.financeos.domain.transaction.TransactionRepository;
import com.financeos.domain.transaction.TransactionType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Runs a rule's predicate across a user's transactions: paginated preview of what a
 * (possibly unsaved) pattern would match, and bulk application of a saved rule to
 * selected or all matches. Matching is against sourcedDescription only and always
 * skips MANUALLY_REVIEWED transactions — those are settled.
 */
@Service
@Slf4j
public class RuleMatchService {

    private static final int APPLY_CHUNK_SIZE = 500;

    private final TransactionRepository transactionRepository;
    private final CategoryRuleRepository categoryRuleRepository;
    private final ReviewStatusManager reviewStatusManager;

    public RuleMatchService(TransactionRepository transactionRepository,
                            CategoryRuleRepository categoryRuleRepository,
                            ReviewStatusManager reviewStatusManager) {
        this.transactionRepository = transactionRepository;
        this.categoryRuleRepository = categoryRuleRepository;
        this.reviewStatusManager = reviewStatusManager;
    }

    /**
     * Detached snapshot of a matched transaction, safe to use after the transaction
     * closes (categories are copied out of the lazy collection).
     */
    public record MatchedTransaction(
            UUID id,
            LocalDate date,
            BigDecimal amount,
            TransactionType type,
            String sourcedDescription,
            Set<Category> categories,
            ReviewType reviewType,
            UUID appliedRuleId) {
    }

    @Transactional(readOnly = true)
    public Page<MatchedTransaction> findMatches(UUID userId, MatchType matchType, String pattern, Pageable pageable) {
        List<UUID> matchedIds = matchingTransactionIds(userId, matchType, pattern);

        int from = (int) Math.min(pageable.getOffset(), matchedIds.size());
        int to = (int) Math.min(pageable.getOffset() + pageable.getPageSize(), matchedIds.size());
        List<UUID> pageIds = matchedIds.subList(from, to);

        List<MatchedTransaction> content = List.of();
        if (!pageIds.isEmpty()) {
            Map<UUID, Transaction> byId = transactionRepository.findAllByIdInAndUserId(pageIds, userId).stream()
                    .collect(Collectors.toMap(Transaction::getId, Function.identity()));
            content = pageIds.stream()
                    .map(byId::get)
                    .filter(java.util.Objects::nonNull)
                    .map(RuleMatchService::snapshot)
                    .toList();
        }
        return new PageImpl<>(content, pageable, matchedIds.size());
    }

    /**
     * Applies the rule to the given transactions: sets the rule's categories and
     * applied_rule_id, propagates MCC, and clears any CATEGORY_UNVERIFIED review flag
     * (promoting to AUTO_REVIEWED so the transaction keeps following the rule).
     * Transactions that don't belong to the rule's user, are manually reviewed, or no
     * longer match the pattern are silently skipped. Returns the number applied.
     */
    @Transactional
    public int applyToTransactions(UUID ruleId, List<UUID> transactionIds) {
        return apply(loadRule(ruleId), transactionIds);
    }

    /** Applies the rule to every transaction its pattern currently matches. */
    @Transactional
    public int applyToAllMatches(UUID ruleId) {
        CategoryRule rule = loadRule(ruleId);
        List<UUID> matchedIds = matchingTransactionIds(rule.getUser().getId(), rule.getMatchType(), rule.getMerchantKey());
        return apply(rule, matchedIds);
    }

    private CategoryRule loadRule(UUID ruleId) {
        return categoryRuleRepository.findWithCategoriesById(ruleId)
                .orElseThrow(() -> new com.financeos.core.exception.ResourceNotFoundException("Rule", ruleId));
    }

    private List<UUID> matchingTransactionIds(UUID userId, MatchType matchType, String pattern) {
        List<TransactionRepository.RuleMatchCandidate> candidates =
                transactionRepository.findRuleMatchCandidates(userId, ReviewType.MANUALLY_REVIEWED);
        List<UUID> matched = new ArrayList<>();
        for (TransactionRepository.RuleMatchCandidate candidate : candidates) {
            RuleMatcher.MatchContext ctx = RuleMatcher.MatchContext.of(candidate.getSourcedDescription());
            if (RuleMatcher.matches(matchType, pattern, ctx)) {
                matched.add(candidate.getId());
            }
        }
        return matched;
    }

    private int apply(CategoryRule rule, List<UUID> transactionIds) {
        UUID ruleUserId = rule.getUser().getId();
        Set<Category> ruleCategories = new HashSet<>(rule.getCategories());

        int applied = 0;
        for (int start = 0; start < transactionIds.size(); start += APPLY_CHUNK_SIZE) {
            List<UUID> chunk = transactionIds.subList(start, Math.min(start + APPLY_CHUNK_SIZE, transactionIds.size()));
            List<Transaction> txns = transactionRepository.findAllByIdInAndUserId(chunk, ruleUserId);
            List<Transaction> toSave = new ArrayList<>();

            for (Transaction txn : txns) {
                if (txn.getReviewType() == ReviewType.MANUALLY_REVIEWED) {
                    continue;
                }
                // Re-verify the predicate so a stale or hand-crafted id can't attach the rule
                // to a transaction its pattern doesn't match.
                if (!RuleMatcher.matches(rule, RuleMatcher.MatchContext.of(txn.getSourcedDescription()))) {
                    continue;
                }

                txn.setCategories(ruleCategories);
                txn.setAppliedRule(rule);
                if ((txn.getMcc() == null || txn.getMcc().isBlank()) && rule.getMcc() != null && !rule.getMcc().isBlank()) {
                    txn.setMcc(rule.getMcc());
                }
                if (txn.getReviewReasons() != null && txn.getReviewReasons().contains(ReviewReason.CATEGORY_UNVERIFIED)) {
                    reviewStatusManager.clearReason(txn, ReviewReason.CATEGORY_UNVERIFIED, ReviewType.AUTO_REVIEWED);
                }
                toSave.add(txn);
                applied++;
            }

            if (!toSave.isEmpty()) {
                transactionRepository.saveAll(toSave);
            }
        }

        if (applied > 0) {
            rule.setAppliedCount(rule.getAppliedCount() + applied);
            rule.setLastAppliedAt(Instant.now());
            categoryRuleRepository.save(rule);
        }
        log.info("Applied rule {} ({}) to {} transaction(s)", rule.getId(), rule.getMerchantKey(), applied);
        return applied;
    }

    private static MatchedTransaction snapshot(Transaction txn) {
        Set<Category> categories = txn.getCategories().stream()
                .map(tc -> tc.getCategory())
                .collect(Collectors.toSet());
        return new MatchedTransaction(
                txn.getId(),
                txn.getDate(),
                txn.getAmount(),
                txn.getType(),
                txn.getSourcedDescription(),
                categories,
                txn.getReviewType(),
                txn.getAppliedRule() != null ? txn.getAppliedRule().getId() : null
        );
    }
}
