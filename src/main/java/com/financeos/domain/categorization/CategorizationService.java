package com.financeos.domain.categorization;

import com.financeos.domain.transaction.CategorySource;
import com.financeos.domain.transaction.ReviewReason;
import com.financeos.domain.transaction.ReviewType;
import com.financeos.domain.transaction.Transaction;
import com.financeos.domain.transaction.TransactionCategoryWriter;
import com.financeos.domain.transaction.TransactionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Rule matching for single descriptions, rule edits/deletes that keep the rows a rule set in step,
 * and the approve undo. The built-in engine and categorise-on-arrival plug in through
 * {@link #recategorise}.
 */
@Service
@Slf4j
public class CategorizationService {

    private final CategoryRuleRepository categoryRuleRepository;
    private final TransactionRepository transactionRepository;
    private final TransactionCategoryWriter categoryWriter;

    public CategorizationService(CategoryRuleRepository categoryRuleRepository,
                                 TransactionRepository transactionRepository,
                                 TransactionCategoryWriter categoryWriter) {
        this.categoryRuleRepository = categoryRuleRepository;
        this.transactionRepository = transactionRepository;
        this.categoryWriter = categoryWriter;
    }

    /** Outcome of a suggestion from the user's rules. */
    public record RuleSuggestion(String categoryKey, String subcategoryKey, UUID ruleId, String mcc) {
    }

    public Optional<CategoryRule> findBestMatchingRule(UUID userId, String description) {
        List<CategoryRule> rules = categoryRuleRepository.findByUserId(userId);
        return bestMatch(rules, description);
    }

    /**
     * Precedence when several rules match the same description (most specific wins): the more
     * literal match type (EXACT > STARTS_WITH > CONTAINS > REGEX), then the longer pattern, then
     * the most recently updated.
     */
    static final Comparator<CategoryRule> RULE_PRECEDENCE = Comparator
            .<CategoryRule, Integer>comparing(r -> (r.getMatchType() == null ? MatchType.CONTAINS : r.getMatchType()).specificity())
            .thenComparing(r -> r.getMerchantKey().length())
            .thenComparing(CategoryRule::getUpdatedAt, Comparator.nullsFirst(Comparator.naturalOrder()));

    private static Optional<CategoryRule> bestMatch(List<CategoryRule> rules, String description) {
        RuleMatcher.MatchContext ctx = RuleMatcher.MatchContext.of(description);
        if (ctx.isBlank()) {
            return Optional.empty();
        }
        return rules.stream()
                .filter(rule -> RuleMatcher.matches(rule, ctx))
                .max(RULE_PRECEDENCE);
    }

    /** What the user's rules say about a free-text description, if any rule matches. */
    @Transactional(readOnly = true)
    public Optional<RuleSuggestion> suggestFromRules(UUID userId, String description) {
        if (description == null || description.isBlank()) {
            return Optional.empty();
        }
        return findBestMatchingRule(userId, description)
                .map(r -> new RuleSuggestion(r.getCategoryKey(), r.getSubcategoryKey(), r.getId(), r.getMcc()));
    }

    /**
     * What undo-approve needs to put one transaction back: the review state it had before the
     * approve, and the updatedAt it was left with (a later edit moves updatedAt, so undo can tell).
     */
    public record ClearedTransaction(
            @jakarta.validation.constraints.NotNull(message = "Transaction ID is required") UUID transactionId,
            ReviewType previousReviewType,
            Set<ReviewReason> previousReviewReasons,
            @org.springframework.lang.Nullable Instant previousReviewedAt,
            Instant verifiedUpdatedAt) {}

    /** Outcome of {@link #undoApprove}: restored ids, and the supplied ones left alone (changed since or not the user's). */
    public record UndoApproveResult(List<UUID> restoredTransactionIds, List<UUID> skippedTransactionIds) {}

    /**
     * All-or-nothing undo of a transaction approve (batch-review with a clearing type): every
     * approved row gets its prior review state back unless edited since (see isUntouchedSinceApprove).
     */
    @Transactional
    public UndoApproveResult undoApprove(List<ClearedTransaction> approved, UUID userId) {
        Map<UUID, ClearedTransaction> byId = new LinkedHashMap<>();
        approved.forEach(c -> byId.put(c.transactionId(), c));
        List<UUID> wanted = new ArrayList<>(byId.keySet());
        List<Transaction> restored = new ArrayList<>();
        for (int from = 0; from < wanted.size(); from += 1000) {
            List<UUID> chunk = wanted.subList(from, Math.min(from + 1000, wanted.size()));
            for (Transaction txn : transactionRepository.findAllByIdInAndUserId(chunk, userId)) {
                ClearedTransaction c = byId.get(txn.getId());
                if (!isUntouchedSinceApprove(txn, c)) {
                    continue;
                }
                txn.setReviewType(c.previousReviewType());
                txn.getReviewReasons().clear();
                txn.getReviewReasons().addAll(c.previousReviewReasons());
                txn.setReviewedAt(c.previousReviewedAt());
                restored.add(txn);
            }
        }
        if (!restored.isEmpty()) {
            transactionRepository.saveAll(restored);
        }
        List<UUID> restoredIds = restored.stream().map(Transaction::getId).toList();
        Set<UUID> restoredSet = new HashSet<>(restoredIds);
        List<UUID> skippedIds = wanted.stream().filter(id -> !restoredSet.contains(id)).toList();
        return new UndoApproveResult(restoredIds, skippedIds);
    }

    /**
     * True when the row is still what an approve left: not edited since (updatedAt within 1
     * microsecond of the stamp), prior state was NEEDS_REVIEW with reasons, and the current reasons
     * are a strict subset of the prior ones (approve only removes reasons) with the matching type:
     * NEEDS_REVIEW while any remain, a cleared type once none do.
     */
    private static boolean isUntouchedSinceApprove(Transaction txn, ClearedTransaction c) {
        Set<ReviewReason> prev = c.previousReviewReasons();
        if (c.previousReviewType() != ReviewType.NEEDS_REVIEW || prev == null || prev.isEmpty()
                || c.verifiedUpdatedAt() == null || txn.getUpdatedAt() == null
                || Math.abs(ChronoUnit.NANOS.between(c.verifiedUpdatedAt(), txn.getUpdatedAt())) > 1000) {
            return false;
        }
        Set<ReviewReason> current = txn.getReviewReasons() == null ? Set.of() : txn.getReviewReasons();
        if (current.size() >= prev.size() || !prev.containsAll(current)) {
            return false;
        }
        return current.isEmpty() ? txn.getReviewType() != ReviewType.NEEDS_REVIEW
                : txn.getReviewType() == ReviewType.NEEDS_REVIEW;
    }

    /**
     * Persists an edited rule and keeps the transactions it set in step, in one transaction. When
     * the pattern changed, rows the rule set that it no longer matches lose that category (they go
     * back through {@link #recategorise}). Then, when the category/sub-category changed, the rows it
     * still governs take the new ones. Matches that are not linked are left alone; applying the
     * rule to them is the explicit apply action.
     */
    @Transactional
    public void saveEditedRule(CategoryRule rule, boolean patternChanged, boolean categoryChanged) {
        categoryRuleRepository.save(rule);
        List<Transaction> linked = transactionRepository.findByAppliedRuleId(rule.getId());
        List<Transaction> noLongerMatching = new ArrayList<>();
        List<Transaction> toSave = new ArrayList<>();
        for (Transaction txn : linked) {
            if (patternChanged
                    && !RuleMatcher.matches(rule, RuleMatcher.MatchContext.of(txn.getSourcedDescription()))) {
                noLongerMatching.add(txn);
            } else if (categoryChanged && categoryWriter.fits(txn, rule.getCategoryKey(), rule.getSubcategoryKey())) {
                if (categoryWriter.set(txn, rule.getCategoryKey(), rule.getSubcategoryKey(), CategorySource.RULE, rule)) {
                    toSave.add(txn);
                }
            }
        }
        if (!noLongerMatching.isEmpty()) {
            recategorise(noLongerMatching);
            log.info("Cleared {} transaction(s) from rule {} after its pattern changed",
                    noLongerMatching.size(), rule.getId());
        }
        if (!toSave.isEmpty()) {
            transactionRepository.saveAll(toSave);
        }
    }

    /**
     * Deletes a rule: its rows lose the link, and rows whose category the rule set (source RULE)
     * lose that category and go back through {@link #recategorise}.
     */
    @Transactional
    public void deleteRule(CategoryRule rule) {
        List<Transaction> linked = transactionRepository.findByAppliedRuleId(rule.getId());
        List<Transaction> clear = new ArrayList<>();
        List<Transaction> unlink = new ArrayList<>();
        for (Transaction txn : linked) {
            if (txn.getCategorySource() == CategorySource.RULE) {
                clear.add(txn);
            } else {
                txn.setAppliedRule(null);
                unlink.add(txn);
            }
        }
        if (!unlink.isEmpty()) {
            transactionRepository.saveAll(unlink);
        }
        recategorise(clear);
        transactionRepository.flush();
        categoryRuleRepository.delete(rule);
    }

    /**
     * Seam for re-running rows through links and the built-in engine after the rule that set their
     * category went away. For now it only clears the category (never a USER one) and saves.
     */
    @Transactional
    public void recategorise(List<Transaction> txns) {
        if (txns == null || txns.isEmpty()) {
            return;
        }
        List<Transaction> toSave = new ArrayList<>();
        for (Transaction txn : txns) {
            if (categoryWriter.set(txn, null, null, CategorySource.AUTO, (CategoryRule) null)) {
                toSave.add(txn);
            }
        }
        if (!toSave.isEmpty()) {
            transactionRepository.saveAll(toSave);
        }
    }
}
