package com.financeos.domain.categorization;

import com.financeos.core.observability.Events;
import net.logstash.logback.argument.StructuredArguments;
import com.financeos.domain.category.Category;
import com.financeos.domain.category.CategoryRepository;
import com.financeos.domain.transaction.*;
import com.financeos.domain.user.User;
import com.financeos.domain.user.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
@Slf4j
public class CategorizationService {

    private final CategoryRuleRepository categoryRuleRepository;
    private final CategoryRepository categoryRepository;
    private final TransactionRepository transactionRepository;
    private final UserRepository userRepository;
    private final TransactionCategorizer transactionCategorizer;
    private final ReviewStatusManager reviewStatusManager;
    private final CategorizationService self;

    // self is a @Lazy-injected proxy of this same bean: batchCategorize/suggestForDescription must call
    // through it (self.xxx(...)) rather than this.xxx(...) whenever they need @Transactional to actually
    // take effect, because Spring's transactional advice only applies to calls that go through the proxy.
    // A plain `this.` call bypasses the proxy entirely and runs non-transactionally (or joins whatever
    // transaction is already open) - which is exactly the bug fixed below in F2/F3.
    public CategorizationService(CategoryRuleRepository categoryRuleRepository,
                                 CategoryRepository categoryRepository,
                                 TransactionRepository transactionRepository,
                                 UserRepository userRepository,
                                 TransactionCategorizer transactionCategorizer,
                                 ReviewStatusManager reviewStatusManager,
                                 @Lazy CategorizationService self) {
        this.categoryRuleRepository = categoryRuleRepository;
        this.categoryRepository = categoryRepository;
        this.transactionRepository = transactionRepository;
        this.userRepository = userRepository;
        this.transactionCategorizer = transactionCategorizer;
        this.reviewStatusManager = reviewStatusManager;
        this.self = self != null ? self : this;
    }

    /**
     * Result of an on-demand categorization suggestion. Never null; empty categories/null ruleId/false
     * fromRule signals "no suggestion available".
     */
    public record SuggestionResult(Set<Category> categories, UUID ruleId, boolean fromRule, String mcc) {
        public SuggestionResult(Set<Category> categories, UUID ruleId, boolean fromRule) {
            this(categories, ruleId, fromRule, null);
        }
    }

    public Optional<CategoryRule> findBestMatchingRule(UUID userId, String description) {
        List<CategoryRule> rules = categoryRuleRepository.findByUserId(userId);
        return bestMatch(rules, description);
    }

    /**
     * Precedence when several rules match the same description: manually created rules
     * always beat auto-generated ones, then the more literal match type wins, then the
     * longer pattern, then verified, then most recently updated.
     */
    static final Comparator<CategoryRule> RULE_PRECEDENCE = Comparator
            .<CategoryRule, Integer>comparing(r -> "USER".equals(r.getSource()) ? 1 : 0)
            .thenComparing(r -> (r.getMatchType() == null ? MatchType.MERCHANT_KEY : r.getMatchType()).specificity())
            .thenComparing(r -> r.getMerchantKey().length())
            .thenComparing(CategoryRule::isVerified)
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

    private CategoryRule getOrCreateRule(UUID userId, String normalizedKey, String displayName,
                                         Set<Category> categories, Map<String, CategoryRule> batchCache) {
        CategoryRule cached = batchCache.get(normalizedKey);
        if (cached != null) {
            return cached;
        }

        Optional<CategoryRule> existing = categoryRuleRepository.findByUserIdAndMerchantKeyAndMatchType(
                userId, normalizedKey, MatchType.MERCHANT_KEY);
        if (existing.isPresent()) {
            batchCache.put(normalizedKey, existing.get());
            return existing.get();
        }

        CategoryRule rule = new CategoryRule();
        rule.setUser(userRepository.getReferenceById(userId));
        rule.setMerchantKey(normalizedKey);
        rule.setMatchType(MatchType.MERCHANT_KEY);
        rule.setDisplayName(displayName != null ? displayName : normalizedKey);
        rule.setCategories(categories);
        rule.setVerified(false);
        rule.setSource("LLM");
        rule.setAppliedCount(0);
        CategoryRule saved = categoryRuleRepository.save(rule);
        batchCache.put(normalizedKey, saved);
        return saved;
    }

    /** A rule this call flipped to verified, and the transactions whose CATEGORY_UNVERIFIED it cleared. */
    public record RuleVerification(UUID ruleId, List<UUID> clearedTransactionIds, List<ClearedTransaction> clearedTransactions) {
        public RuleVerification(UUID ruleId, List<UUID> clearedTransactionIds) {
            this(ruleId, clearedTransactionIds, List.of());
        }
    }

    /**
     * What verify changed on one transaction, enough to reverse it exactly: the review state it had
     * before, and the updatedAt it was left with (a later edit moves updatedAt, so undo can tell).
     */
    public record ClearedTransaction(
            UUID transactionId,
            ReviewType previousReviewType,
            Set<ReviewReason> previousReviewReasons,
            @org.springframework.lang.Nullable Instant previousReviewedAt,
            Instant verifiedUpdatedAt) {}

    /** Outcome of {@link #unverifyRule}: restored ids, and the supplied ones left alone (changed since, moved off the rule or not the user's). */
    public record UnverifyResult(List<UUID> restoredTransactionIds, List<UUID> skippedTransactionIds) {}

    @Transactional
    public List<RuleVerification> verifyRule(CategoryRule rule) {
        return verifyRules(List.of(rule));
    }

    /**
     * Marks the rules verified and clears CATEGORY_UNVERIFIED from every transaction linked to any
     * of them, in one transaction. Returns, for each rule that was unverified before the call, the
     * ids of the transactions it cleared (so the caller can undo it).
     */
    @Transactional
    public List<RuleVerification> verifyRules(Collection<CategoryRule> rules) {
        if (rules.isEmpty()) {
            return List.of();
        }
        Set<UUID> wasUnverified = new LinkedHashSet<>();
        for (CategoryRule rule : rules) {
            if (!rule.isVerified()) {
                wasUnverified.add(rule.getId());
            }
        }
        rules.forEach(rule -> rule.setVerified(true));
        categoryRuleRepository.saveAll(rules);

        List<UUID> ruleIds = rules.stream().map(CategoryRule::getId).toList();
        List<Transaction> txns = transactionRepository.findByAppliedRuleIdIn(ruleIds);
        List<Transaction> toSave = new ArrayList<>();
        Map<UUID, List<Transaction>> clearedByRule = new LinkedHashMap<>();
        Map<UUID, ClearedTransaction> prior = new HashMap<>();
        wasUnverified.forEach(id -> clearedByRule.put(id, new ArrayList<>()));
        for (Transaction txn : txns) {
            if (txn.getReviewReasons() != null && txn.getReviewReasons().contains(ReviewReason.CATEGORY_UNVERIFIED)) {
                prior.put(txn.getId(), new ClearedTransaction(txn.getId(), txn.getReviewType(),
                        new HashSet<>(txn.getReviewReasons()), txn.getReviewedAt(), null));
                reviewStatusManager.clearReason(txn, ReviewReason.CATEGORY_UNVERIFIED, ReviewType.AUTO_REVIEWED);
                toSave.add(txn);
                List<Transaction> cleared = clearedByRule.get(txn.getAppliedRule().getId());
                if (cleared != null) {
                    cleared.add(txn);
                }
            }
        }
        if (!toSave.isEmpty()) {
            // Flush so @PreUpdate has stamped updatedAt; that stamp is what undo compares against.
            transactionRepository.saveAllAndFlush(toSave);
        }
        List<RuleVerification> result = new ArrayList<>();
        clearedByRule.forEach((id, cleared) -> result.add(new RuleVerification(
                id,
                cleared.stream().map(Transaction::getId).toList(),
                cleared.stream().map(t -> {
                    ClearedTransaction p = prior.get(t.getId());
                    return new ClearedTransaction(t.getId(), p.previousReviewType(), p.previousReviewReasons(),
                            p.previousReviewedAt(),
                            t.getUpdatedAt() == null ? null : t.getUpdatedAt().truncatedTo(ChronoUnit.MICROS));
                }).toList())));
        return result;
    }

    /**
     * Undo of {@link #verifyRule}: marks the rule unverified and puts CATEGORY_UNVERIFIED back on
     * those of the given transactions that belong to the user and are still linked to it. Returns
     * the restored ids.
     */
    @Transactional
    public List<UUID> unverifyRule(CategoryRule rule, List<UUID> transactionIds, UUID userId) {
        return unverifyRule(rule, transactionIds, List.of(), userId).restoredTransactionIds();
    }

    /**
     * Full undo of {@link #verifyRule}: as the 3-arg form, plus each given {@link ClearedTransaction}
     * gets its exact prior review type, reasons and reviewedAt back. It is skipped, and the user's
     * later edit kept, when it is no longer on this rule, is not the user's, or its updatedAt no
     * longer matches the stamp verify left, or it is not what verify left (see isUntouchedSinceVerify).
     */
    @Transactional
    public UnverifyResult unverifyRule(CategoryRule rule, List<UUID> transactionIds, List<ClearedTransaction> cleared, UUID userId) {
        rule.setVerified(false);
        categoryRuleRepository.save(rule);
        Map<UUID, ClearedTransaction> exact = new LinkedHashMap<>();
        cleared.forEach(c -> exact.put(c.transactionId(), c));
        List<UUID> wanted = new ArrayList<>(exact.keySet());
        transactionIds.stream().distinct().filter(id -> !exact.containsKey(id)).forEach(wanted::add);

        List<Transaction> restored = new ArrayList<>();
        // Chunked: Oracle rejects IN lists over 1000 entries (ORA-01795).
        for (int from = 0; from < wanted.size(); from += 1000) {
            List<UUID> chunk = wanted.subList(from, Math.min(from + 1000, wanted.size()));
            for (Transaction txn : transactionRepository.findAllByIdInAndUserId(chunk, userId)) {
                if (txn.getAppliedRule() == null || !rule.getId().equals(txn.getAppliedRule().getId())) {
                    continue;
                }
                ClearedTransaction c = exact.get(txn.getId());
                if (c == null) {
                    reviewStatusManager.addReason(txn, ReviewReason.CATEGORY_UNVERIFIED);
                } else {
                    if (!isUntouchedSinceVerify(txn, c)) {
                        continue;
                    }
                    txn.setReviewType(c.previousReviewType());
                    txn.getReviewReasons().clear();
                    txn.getReviewReasons().addAll(c.previousReviewReasons());
                    txn.setReviewedAt(c.previousReviewedAt());
                }
                restored.add(txn);
            }
        }
        if (!restored.isEmpty()) {
            transactionRepository.saveAll(restored);
        }
        List<UUID> restoredIds = restored.stream().map(Transaction::getId).toList();
        Set<UUID> restoredSet = new HashSet<>(restoredIds);
        List<UUID> skipped = wanted.stream().filter(id -> !restoredSet.contains(id)).toList();
        return new UnverifyResult(restoredIds, skipped);
    }

    /**
     * True when the entry is a state verify could have cleared (it was in review with
     * CATEGORY_UNVERIFIED) and the row is still exactly what verify left: not edited since
     * (updatedAt within 1 microsecond, covering the DB rounding the ns Instant), no
     * CATEGORY_UNVERIFIED, remaining reasons the prior ones minus that one, and the matching type.
     */
    private static boolean isUntouchedSinceVerify(Transaction txn, ClearedTransaction c) {
        Set<ReviewReason> prev = c.previousReviewReasons();
        if (c.previousReviewType() != ReviewType.NEEDS_REVIEW || prev == null
                || !prev.contains(ReviewReason.CATEGORY_UNVERIFIED)) {
            return false;
        }
        if (c.verifiedUpdatedAt() == null || txn.getUpdatedAt() == null
                || Math.abs(ChronoUnit.NANOS.between(c.verifiedUpdatedAt(), txn.getUpdatedAt())) > 1000) {
            return false;
        }
        Set<ReviewReason> expected = new HashSet<>(prev);
        expected.remove(ReviewReason.CATEGORY_UNVERIFIED);
        Set<ReviewReason> current = txn.getReviewReasons() == null ? Set.of() : txn.getReviewReasons();
        ReviewType expectedType = expected.isEmpty() ? ReviewType.AUTO_REVIEWED : ReviewType.NEEDS_REVIEW;
        return current.equals(expected) && txn.getReviewType() == expectedType;
    }

    /**
     * Persists an edited rule and keeps its linked transactions consistent with it, in one
     * transaction. When the pattern changed, linked transactions it no longer matches are unlinked
     * first: they keep their current categories but stop following the rule. Then, when categories
     * were supplied, the remaining linked transactions get them. Transactions the pattern matches
     * but that aren't linked are left alone; linking them is the explicit apply action.
     */
    @Transactional
    public void saveEditedRule(CategoryRule rule, boolean patternChanged, Set<Category> newCategories) {
        if (patternChanged) {
            List<Transaction> noLongerMatching = new ArrayList<>();
            for (Transaction txn : transactionRepository.findByAppliedRuleId(rule.getId())) {
                if (!RuleMatcher.matches(rule, RuleMatcher.MatchContext.of(txn.getSourcedDescription()))) {
                    txn.setAppliedRule(null);
                    noLongerMatching.add(txn);
                }
            }
            if (!noLongerMatching.isEmpty()) {
                transactionRepository.saveAll(noLongerMatching);
                log.info("Unlinked {} transaction(s) from rule {} after its pattern changed",
                        noLongerMatching.size(), rule.getId());
            }
        }

        if (newCategories != null) {
            updateRuleCategories(rule, newCategories);
        } else {
            categoryRuleRepository.save(rule);
        }
    }

    @Transactional
    public void updateRuleCategories(CategoryRule rule, Set<Category> newCategories) {
        rule.setCategories(newCategories);
        categoryRuleRepository.save(rule);

        List<Transaction> txns = transactionRepository.findByAppliedRuleId(rule.getId());
        List<Transaction> toSave = new ArrayList<>();
        for (Transaction txn : txns) {
            if (txn.getReviewType() != ReviewType.MANUALLY_REVIEWED) {
                txn.setCategories(newCategories);
                toSave.add(txn);
            }
        }
        if (!toSave.isEmpty()) {
            transactionRepository.saveAll(toSave);
        }
    }

    /**
     * Non-transactional orchestrator. Reads rules/categories (explicitly user-scoped, so correct even
     * without the Hibernate userFilter, which only auto-enables on @Transactional entry), then makes the
     * Gemini HTTP call OUTSIDE of any DB transaction, and finally hands every mutation off in one shot to
     * the @Transactional applyCategorizationResults via the self proxy.
     */
    public void batchCategorize(List<Transaction> txns) {
        if (txns == null || txns.isEmpty()) {
            return;
        }

        User user = txns.get(0).getUser();
        if (user == null) {
            log.warn("Cannot categorize transactions: user is null");
            return;
        }
        UUID userId = user.getId();

        List<Category> userCategories = categoryRepository.findByUserId(userId);
        List<CategoryRule> rules = categoryRuleRepository.findByUserId(userId);

        Map<Integer, UUID> ruleMatchesByIndex = new HashMap<>();
        List<TransactionCategorizer.CategorizeItemRequest> llmBatchRequests = new ArrayList<>();

        for (int i = 0; i < txns.size(); i++) {
            Transaction txn = txns.get(i);
            String description = effectiveDescription(txn);
            if (description == null || description.isBlank() || !txn.getCategories().isEmpty()) {
                continue;
            }

            Optional<CategoryRule> matchingRule = bestMatch(rules, description);
            if (matchingRule.isPresent()) {
                ruleMatchesByIndex.put(i, matchingRule.get().getId());
            } else {
                llmBatchRequests.add(new TransactionCategorizer.CategorizeItemRequest(i, description));
            }
        }

        List<TransactionCategorizer.CategorizeItemResponse> llmResponses = List.of();
        if (!llmBatchRequests.isEmpty()) {
            List<String> categoryNames = userCategories.stream().map(Category::getName).toList();
            llmResponses = transactionCategorizer.categorize(llmBatchRequests, categoryNames);
        }

        self.applyCategorizationResults(txns, userCategories, userId, ruleMatchesByIndex, llmBatchRequests, llmResponses);
    }

    /**
     * All DB mutation for a batchCategorize run happens here, inside one transaction. Must be called via
     * the self proxy (see field comment) - a direct `this.` call from batchCategorize would bypass the
     * proxy and silently run without a transaction.
     * <p>
     * Matched rules are re-fetched by id (via findWithCategoriesById) rather than reusing the CategoryRule
     * instances resolved during the non-transactional matching pass above: those instances were loaded in
     * a separate, already-closed transaction (open-in-view is disabled for this app), so their lazy
     * `categories` collection cannot be initialized here without a fresh fetch.
     */
    @Transactional
    public void applyCategorizationResults(List<Transaction> txns,
                                           List<Category> userCategories,
                                           UUID userId,
                                           Map<Integer, UUID> ruleMatchesByIndex,
                                           List<TransactionCategorizer.CategorizeItemRequest> llmBatchRequests,
                                           List<TransactionCategorizer.CategorizeItemResponse> llmResponses) {
        Map<String, CategoryRule> batchCache = new HashMap<>();
        Map<String, Category> createdCategoriesByName = new HashMap<>();

        for (Map.Entry<Integer, UUID> entry : ruleMatchesByIndex.entrySet()) {
            Transaction txn = txns.get(entry.getKey());
            CategoryRule rule = categoryRuleRepository.findWithCategoriesById(entry.getValue()).orElse(null);
            if (rule == null) {
                // Rule was deleted between the read-only matching pass and this transaction; fall back
                // to flagging for review rather than failing the whole batch.
                reviewStatusManager.addReason(txn, ReviewReason.CATEGORY_UNVERIFIED);
                continue;
            }

            String catBefore = txn.getCategories() != null ? txn.getCategories().toString() : "";
            txn.setCategories(rule.getCategories());
            txn.setAppliedRule(rule);
            if ((txn.getMcc() == null || txn.getMcc().isBlank()) && rule.getMcc() != null && !rule.getMcc().isBlank()) {
                txn.setMcc(rule.getMcc());
            }
            rule.setAppliedCount(rule.getAppliedCount() + 1);
            rule.setLastAppliedAt(Instant.now());
            categoryRuleRepository.save(rule);

            String catAfter = rule.getCategories() != null ? rule.getCategories().toString() : "";
            boolean overridden = catBefore != null && !catBefore.isBlank() && !catBefore.equals("[]");
            log.info("Categorize decision: txnId={}, source=RULE, ruleId={}, categoryBefore={}, categoryAfter={}, overridden={}",
                    txn.getId(), rule.getId(), catBefore, catAfter, overridden,
                    StructuredArguments.keyValue("event", Events.CATEGORIZE_DECISION),
                    StructuredArguments.keyValue("txnId", txn.getId() != null ? txn.getId().toString() : ""),
                    StructuredArguments.keyValue("source", "RULE"),
                    StructuredArguments.keyValue("ruleId", rule.getId() != null ? rule.getId().toString() : ""),
                    StructuredArguments.keyValue("matchType", rule.getMatchType() != null ? rule.getMatchType().name() : "exact"),
                    StructuredArguments.keyValue("categoryBefore", catBefore),
                    StructuredArguments.keyValue("categoryAfter", catAfter),
                    StructuredArguments.keyValue("overridden", overridden));

            if (!rule.isVerified()) {
                reviewStatusManager.addReason(txn, ReviewReason.CATEGORY_UNVERIFIED);
            }
        }

        if (!llmBatchRequests.isEmpty()) {
            for (TransactionCategorizer.CategorizeItemRequest req : llmBatchRequests) {
                Transaction txn = txns.get(req.index());

                TransactionCategorizer.CategorizeItemResponse res = llmResponses.stream()
                        .filter(r -> r.index() != null && r.index() == req.index())
                        .findFirst()
                        .orElse(null);

                boolean validResult = false;
                if (res != null) {
                    if (Boolean.TRUE.equals(res.noFit())) {
                        validResult = true;
                        reviewStatusManager.addReason(txn, ReviewReason.CATEGORY_UNVERIFIED);
                    } else if (res.categoryNames() != null && !res.categoryNames().isEmpty() && res.merchantKey() != null) {
                        // Validate the whole response item (names + merchant key) before creating
                        // anything: a rejected item must not leave behind categories it caused to exist.
                        String normalizedKey = DescriptionNormalizer.normalize(res.merchantKey());
                        String normalizedDesc = DescriptionNormalizer.normalize(effectiveDescription(txn));
                        boolean keyValid = normalizedKey.length() >= 3 && normalizedDesc.contains(normalizedKey);
                        if (keyValid) {
                            Optional<Set<Category>> resolvedOpt = resolveOrCreate(
                                    userId, userCategories, res.categoryNames(), createdCategoriesByName);

                            if (resolvedOpt.isPresent()) {
                                Set<Category> resolvedCategories = resolvedOpt.get();
                                validResult = true;
                                String catBefore = txn.getCategories() != null ? txn.getCategories().toString() : "";
                                CategoryRule rule = getOrCreateRule(userId, normalizedKey, res.displayName(), resolvedCategories, batchCache);
                                txn.setCategories(resolvedCategories);
                                txn.setAppliedRule(rule);
                                rule.setAppliedCount(rule.getAppliedCount() + 1);
                                rule.setLastAppliedAt(Instant.now());
                                categoryRuleRepository.save(rule);

                                String catAfter = resolvedCategories.toString();
                                boolean overridden = catBefore != null && !catBefore.isBlank() && !catBefore.equals("[]");
                                log.info("Categorize decision: txnId={}, source=LLM, ruleId={}, categoryBefore={}, categoryAfter={}, overridden={}",
                                        txn.getId(), rule.getId(), catBefore, catAfter, overridden,
                                        StructuredArguments.keyValue("event", Events.CATEGORIZE_DECISION),
                                        StructuredArguments.keyValue("txnId", txn.getId() != null ? txn.getId().toString() : ""),
                                        StructuredArguments.keyValue("source", "LLM"),
                                        StructuredArguments.keyValue("ruleId", rule.getId() != null ? rule.getId().toString() : ""),
                                        StructuredArguments.keyValue("matchType", rule.getMatchType() != null ? rule.getMatchType().name() : "exact"),
                                        StructuredArguments.keyValue("categoryBefore", catBefore),
                                        StructuredArguments.keyValue("categoryAfter", catAfter),
                                        StructuredArguments.keyValue("overridden", overridden));

                                reviewStatusManager.addReason(txn, ReviewReason.CATEGORY_UNVERIFIED);
                            }
                        }
                    }
                }

                if (!validResult) {
                    reviewStatusManager.addReason(txn, ReviewReason.CATEGORY_UNVERIFIED);
                }
            }
        }

        saveAllTxnsIfPersisted(txns);
    }

    /**
     * Resolves raw category names suggested by the LLM against existing user categories (case-insensitively)
     * or creates new Category entities for non-existent names.
     * <p>
     * Two-pass validation:
     * 1. Sanitizes names (trim, collapse whitespace runs).
     * 2. Rejects if blank, >60 characters, or if a newly created name exceeds 2 words.
     * If any name fails validation, returns Optional.empty() and creates nothing.
     */
    Optional<Set<Category>> resolveOrCreate(UUID userId,
                                            List<Category> userCategories,
                                            List<String> rawNames,
                                            Map<String, Category> createdCache) {
        if (rawNames == null || rawNames.isEmpty()) {
            return Optional.empty();
        }

        List<String> sanitizedNames = new ArrayList<>();
        for (String rawCatName : rawNames) {
            String sanitizedName = rawCatName == null ? "" : rawCatName.trim().replaceAll("\\s+", " ");
            if (sanitizedName.isBlank() || sanitizedName.length() > 60) {
                return Optional.empty();
            }
            // Word cap applies only to names that would be CREATED - the LLM may still
            // reference the user's own multi-word categories.
            boolean resolvable = userCategories.stream().anyMatch(c -> c.getName().equalsIgnoreCase(sanitizedName))
                    || (createdCache != null && createdCache.containsKey(sanitizedName.toLowerCase()));
            if (!resolvable && sanitizedName.split(" ").length > 2) {
                return Optional.empty();
            }
            sanitizedNames.add(sanitizedName);
        }

        Set<Category> resolvedCategories = new HashSet<>();
        for (String sanitizedName : sanitizedNames) {
            Category matchedCat = userCategories.stream()
                    .filter(c -> c.getName().equalsIgnoreCase(sanitizedName))
                    .findFirst()
                    .orElse(null);

            if (matchedCat == null && createdCache != null) {
                matchedCat = createdCache.get(sanitizedName.toLowerCase());
            }

            if (matchedCat == null) {
                User userRef = userRepository.getReferenceById(userId);
                Category newCategory = new Category(sanitizedName, userRef);
                matchedCat = categoryRepository.save(newCategory);
                if (createdCache != null) {
                    createdCache.put(sanitizedName.toLowerCase(), matchedCat);
                }
            }

            resolvedCategories.add(matchedCat);
        }

        return Optional.of(resolvedCategories);
    }

    private Optional<Set<Category>> resolveOnly(List<Category> userCategories, List<String> rawNames) {
        if (rawNames == null || rawNames.isEmpty()) {
            return Optional.empty();
        }

        List<String> sanitizedNames = new ArrayList<>();
        for (String rawCatName : rawNames) {
            String sanitizedName = rawCatName == null ? "" : rawCatName.trim().replaceAll("\\s+", " ");
            if (sanitizedName.isBlank() || sanitizedName.length() > 60) {
                return Optional.empty();
            }
            sanitizedNames.add(sanitizedName);
        }

        Set<Category> resolvedCategories = new HashSet<>();
        for (String sanitizedName : sanitizedNames) {
            Category matchedCat = userCategories.stream()
                    .filter(c -> c.getName().equalsIgnoreCase(sanitizedName))
                    .findFirst()
                    .orElse(null);
            if (matchedCat == null) {
                return Optional.empty();
            }
            resolvedCategories.add(matchedCat);
        }

        return Optional.of(resolvedCategories);
    }

    @Transactional
    public Optional<Set<Category>> resolveOrCreateTransactional(UUID userId,
                                                                List<Category> userCategories,
                                                                List<String> rawNames) {
        return resolveOrCreate(userId, userCategories, rawNames, new HashMap<>());
    }

    /**
     * On-demand suggestion for a single free-text description, used by the /api/v1/categorize endpoint.
     * Checks matching rules first; if none match, falls through to the LLM (even with zero categories)
     * and case-insensitively resolves or creates categories transactionally.
     * Never throws - any failure (no rule match, LLM error, no fit, validation failure) resolves to an empty result.
     */
    public SuggestionResult suggestForDescription(UUID userId, String description) {
        if (description == null || description.isBlank()) {
            return new SuggestionResult(Set.of(), null, false);
        }

        try {
            Optional<SuggestionResult> ruleResult = self.suggestFromRules(userId, description);
            if (ruleResult.isPresent()) {
                return ruleResult.get();
            }

            List<Category> userCategories = categoryRepository.findByUserId(userId);
            List<String> categoryNames = userCategories.stream().map(Category::getName).toList();
            List<TransactionCategorizer.CategorizeItemResponse> responses = transactionCategorizer.categorize(
                    List.of(new TransactionCategorizer.CategorizeItemRequest(0, description)), categoryNames);

            TransactionCategorizer.CategorizeItemResponse res = responses.stream().findFirst().orElse(null);
            if (res == null || Boolean.TRUE.equals(res.noFit()) || res.categoryNames() == null || res.categoryNames().isEmpty()) {
                return new SuggestionResult(Set.of(), null, false);
            }

            Optional<Set<Category>> resolved;
            try {
                resolved = self.resolveOrCreateTransactional(userId, userCategories, res.categoryNames());
            } catch (DataIntegrityViolationException e) {
                // Concurrent creation race: reload categories and resolve read-only without creating
                List<Category> reloadedCategories = categoryRepository.findByUserId(userId);
                resolved = resolveOnly(reloadedCategories, res.categoryNames());
            }

            if (resolved.isEmpty() || resolved.get().isEmpty()) {
                return new SuggestionResult(Set.of(), null, false);
            }

            return new SuggestionResult(resolved.get(), null, false);
        } catch (Exception e) {
            log.warn("Failed to compute categorization suggestion: {}: {}", e.getClass().getSimpleName(), e.getMessage());
            return new SuggestionResult(Set.of(), null, false);
        }
    }

    /**
     * Called via the self proxy from suggestForDescription so the read-only transaction is real (needed
     * to safely initialize CategoryRule.categories, which is lazy). The categories are copied into a
     * plain HashSet before returning so the result stays usable after this transaction/session closes.
     */
    @Transactional(readOnly = true)
    public Optional<SuggestionResult> suggestFromRules(UUID userId, String description) {
        List<CategoryRule> rules = categoryRuleRepository.findByUserId(userId);
        Optional<CategoryRule> match = bestMatch(rules, description);
        if (match.isEmpty()) {
            return Optional.empty();
        }
        CategoryRule rule = match.get();
        Set<Category> categories = new HashSet<>(rule.getCategories());
        return Optional.of(new SuggestionResult(categories, rule.getId(), true, rule.getMcc()));
    }

    /**
     * Rules match exclusively against sourcedDescription (the original text from ingestion).
     * Manually created transactions have no sourcedDescription and are deliberately outside
     * the rule system — their user-written description is never matched.
     */
    private static String effectiveDescription(Transaction txn) {
        return txn.getSourcedDescription();
    }

    private void saveAllTxnsIfPersisted(List<Transaction> txns) {
        List<Transaction> toSave = txns.stream().filter(t -> t.getId() != null).toList();
        if (!toSave.isEmpty()) {
            transactionRepository.saveAll(toSave);
        }
    }
}
