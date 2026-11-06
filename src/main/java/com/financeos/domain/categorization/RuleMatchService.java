package com.financeos.domain.categorization;

import com.financeos.domain.transaction.CategorySource;
import com.financeos.domain.transaction.Transaction;
import com.financeos.domain.transaction.TransactionCategoryWriter;
import com.financeos.domain.transaction.TransactionRepository;
import com.financeos.domain.transaction.TransactionType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.lang.Nullable;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Runs a rule's predicate across a user's transactions: paginated preview of what a
 * (possibly unsaved) pattern would match, and bulk application of a saved rule to
 * selected or all matches. Matching is against sourcedDescription only; applying always
 * skips transactions whose category the user set (source USER) — those are settled.
 */
@Service
@Slf4j
public class RuleMatchService {

    private static final int APPLY_CHUNK_SIZE = 500;
    /** Applied transactions listed in an {@link ApplyOutcome}; the counts always cover all of them. */
    static final int APPLY_OUTCOME_SAMPLE_SIZE = 50;

    private final TransactionRepository transactionRepository;
    private final CategoryRuleRepository categoryRuleRepository;
    private final TransactionCategoryWriter categoryWriter;

    public RuleMatchService(TransactionRepository transactionRepository,
                            CategoryRuleRepository categoryRuleRepository,
                            TransactionCategoryWriter categoryWriter) {
        this.transactionRepository = transactionRepository;
        this.categoryRuleRepository = categoryRuleRepository;
        this.categoryWriter = categoryWriter;
    }

    /**
     * Detached snapshot of a matched transaction, safe to use after the transaction closes.
     */
    public record MatchedTransaction(
            UUID id,
            LocalDate date,
            @Nullable BigDecimal amount,
            TransactionType type,
            String sourcedDescription,
            String categoryKey,
            String subcategoryKey,
            CategorySource categorySource,
            UUID appliedRuleId,
            String appliedRuleName,
            UUID accountId,
            String accountName,
            String accountBank,
            UUID cardId,
            String cardLast4) {
    }

    /**
     * What an apply did: how many transactions were asked for, how many took the rule, why the rest
     * were left alone, and the first {@value #APPLY_OUTCOME_SAMPLE_SIZE} applied transactions.
     */
    public record ApplyOutcome(
            int requested,
            int applied,
            int skippedUserSet,
            int skippedNoLongerMatching,
            int skippedWrongDirection,
            int notFound,
            List<AppliedTransaction> appliedSample) {
    }

    public record AppliedTransaction(
            UUID id,
            LocalDate date,
            @Nullable BigDecimal amount,
            TransactionType type,
            String description) {
    }

    /** A page of matches plus the totals the rule editor shows: all matches, and those the user categorised. */
    public record MatchPage(Page<MatchedTransaction> matches, long matchCount, long userSetCount) {
    }

    @Transactional(readOnly = true)
    public MatchPage findMatches(UUID userId, MatchType matchType, String pattern, Pageable pageable) {
        List<TransactionRepository.RuleMatchCandidate> matched = matchingCandidates(userId, matchType, pattern);
        long userSet = matched.stream().filter(c -> c.getCategorySource() == CategorySource.USER).count();
        List<UUID> matchedIds = matched.stream().map(TransactionRepository.RuleMatchCandidate::getId).toList();

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
        return new MatchPage(new PageImpl<>(content, pageable, matchedIds.size()), matchedIds.size(), userSet);
    }

    /**
     * Applies the rule to the given transactions: sets the rule's category/sub-category (source
     * RULE) and applied_rule_id through the shared writer, and propagates MCC. Transactions that
     * don't belong to the rule's user, whose category the user set, or that no longer match the
     * pattern are skipped. Returns the number applied.
     */
    @Transactional
    public int applyToTransactions(UUID ruleId, List<UUID> transactionIds) {
        return applyToTransactionsDetailed(ruleId, transactionIds).applied();
    }

    /** {@link #applyToTransactions} with the full outcome. */
    @Transactional
    public ApplyOutcome applyToTransactionsDetailed(UUID ruleId, List<UUID> transactionIds) {
        return apply(loadRule(ruleId), transactionIds);
    }

    /** Applies the rule to every transaction its pattern currently matches. */
    @Transactional
    public int applyToAllMatches(UUID ruleId) {
        return applyToAllMatchesDetailed(ruleId).applied();
    }

    /** {@link #applyToAllMatches} with the full outcome. */
    @Transactional
    public ApplyOutcome applyToAllMatchesDetailed(UUID ruleId) {
        CategoryRule rule = loadRule(ruleId);
        List<UUID> matchedIds = matchingCandidates(rule.getUser().getId(), rule.getMatchType(), rule.getMerchantKey())
                .stream().map(TransactionRepository.RuleMatchCandidate::getId).toList();
        return apply(rule, matchedIds);
    }

    private CategoryRule loadRule(UUID ruleId) {
        return categoryRuleRepository.findById(ruleId)
                .orElseThrow(() -> new com.financeos.core.exception.ResourceNotFoundException("Rule", ruleId));
    }

    private List<TransactionRepository.RuleMatchCandidate> matchingCandidates(UUID userId, MatchType matchType,
                                                                               String pattern) {
        List<TransactionRepository.RuleMatchCandidate> candidates = transactionRepository.findRuleMatchCandidates(userId);
        List<TransactionRepository.RuleMatchCandidate> matched = new ArrayList<>();
        for (TransactionRepository.RuleMatchCandidate candidate : candidates) {
            RuleMatcher.MatchContext ctx = RuleMatcher.MatchContext.of(candidate.getSourcedDescription());
            if (RuleMatcher.matches(matchType, pattern, ctx)) {
                matched.add(candidate);
            }
        }
        return matched;
    }

    private ApplyOutcome apply(CategoryRule rule, List<UUID> transactionIds) {
        UUID ruleUserId = rule.getUser().getId();

        int applied = 0;
        int found = 0;
        int skippedUserSet = 0;
        int skippedWrongDirection = 0;
        int skippedNoLongerMatching = 0;
        List<AppliedTransaction> appliedSample = new ArrayList<>();
        for (int start = 0; start < transactionIds.size(); start += APPLY_CHUNK_SIZE) {
            List<UUID> chunk = transactionIds.subList(start, Math.min(start + APPLY_CHUNK_SIZE, transactionIds.size()));
            List<Transaction> txns = transactionRepository.findAllByIdInAndUserId(chunk, ruleUserId);
            List<Transaction> toSave = new ArrayList<>();
            found += txns.size();

            for (Transaction txn : txns) {
                if (txn.getCategorySource() == CategorySource.USER) {
                    skippedUserSet++;
                    continue;
                }
                // Re-verify the predicate so a stale or hand-crafted id can't attach the rule
                // to a transaction its pattern doesn't match.
                if (!RuleMatcher.matches(rule, RuleMatcher.MatchContext.of(txn.getSourcedDescription()))) {
                    skippedNoLongerMatching++;
                    continue;
                }

                if (!categoryWriter.fits(txn, rule.getCategoryKey(), rule.getSubcategoryKey())) {
                    skippedWrongDirection++;
                    continue;
                }
                categoryWriter.set(txn, rule.getCategoryKey(), rule.getSubcategoryKey(), CategorySource.RULE, rule);
                if ((txn.getMcc() == null || txn.getMcc().isBlank()) && rule.getMcc() != null && !rule.getMcc().isBlank()) {
                    txn.setMcc(rule.getMcc());
                }
                toSave.add(txn);
                applied++;
                if (appliedSample.size() < APPLY_OUTCOME_SAMPLE_SIZE) {
                    appliedSample.add(new AppliedTransaction(
                            txn.getId(),
                            txn.getDate(),
                            txn.getAmount(),
                            txn.getType(),
                            txn.getDescription() != null ? txn.getDescription() : txn.getSourcedDescription()));
                }
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
        return new ApplyOutcome(transactionIds.size(), applied, skippedUserSet, skippedNoLongerMatching, skippedWrongDirection,
                transactionIds.size() - found, appliedSample);
    }

    private static MatchedTransaction snapshot(Transaction txn) {
        CategoryRule appliedRule = txn.getAppliedRule();
        return new MatchedTransaction(
                txn.getId(),
                txn.getDate(),
                txn.getAmount(),
                txn.getType(),
                txn.getSourcedDescription(),
                txn.getCategoryKey(),
                txn.getSubcategoryKey(),
                txn.getCategorySource(),
                appliedRule != null ? appliedRule.getId() : null,
                appliedRule != null ? ruleName(appliedRule) : null,
                txn.getAccount().getId(),
                txn.getAccount().getName(),
                txn.getAccount().getBank(),
                txn.getCard() != null ? txn.getCard().getId() : null,
                txn.getCard() != null ? txn.getCard().getLast4() : null
        );
    }

    /** What the rules page shows for a rule: its display name, else its pattern. */
    private static String ruleName(CategoryRule rule) {
        String displayName = rule.getDisplayName();
        return displayName != null && !displayName.isBlank() ? displayName : rule.getMerchantKey();
    }
}
