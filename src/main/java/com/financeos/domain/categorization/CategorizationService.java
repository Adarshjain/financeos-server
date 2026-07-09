package com.financeos.domain.categorization;

import com.financeos.domain.category.Category;
import com.financeos.domain.category.CategoryRepository;
import com.financeos.domain.transaction.*;
import com.financeos.domain.user.User;
import com.financeos.domain.user.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

@Service
@Slf4j
public class CategorizationService {

    private final CategoryRuleRepository categoryRuleRepository;
    private final CategoryRepository categoryRepository;
    private final TransactionRepository transactionRepository;
    private final UserRepository userRepository;
    private final GeminiCategorizer geminiCategorizer;
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
                                 GeminiCategorizer geminiCategorizer,
                                 ReviewStatusManager reviewStatusManager,
                                 @Lazy CategorizationService self) {
        this.categoryRuleRepository = categoryRuleRepository;
        this.categoryRepository = categoryRepository;
        this.transactionRepository = transactionRepository;
        this.userRepository = userRepository;
        this.geminiCategorizer = geminiCategorizer;
        this.reviewStatusManager = reviewStatusManager;
        this.self = self;
    }

    /**
     * Result of an on-demand categorization suggestion. Never null; empty categories/null ruleId/false
     * fromRule signals "no suggestion available".
     */
    public record SuggestionResult(Set<Category> categories, UUID ruleId, boolean fromRule) {}

    public Optional<CategoryRule> findBestMatchingRule(UUID userId, String description) {
        List<CategoryRule> rules = categoryRuleRepository.findByUserId(userId);
        return bestMatch(rules, description);
    }

    private static Optional<CategoryRule> bestMatch(List<CategoryRule> rules, String description) {
        String normalizedDesc = DescriptionNormalizer.normalize(description);
        if (normalizedDesc.isBlank()) {
            return Optional.empty();
        }
        return rules.stream()
                .filter(rule -> rule.getMerchantKey().length() >= 3 && normalizedDesc.contains(rule.getMerchantKey()))
                .max(Comparator
                        .<CategoryRule, Integer>comparing(r -> r.getMerchantKey().length())
                        .thenComparing(CategoryRule::isVerified)
                        .thenComparing(CategoryRule::getUpdatedAt)
                );
    }

    private CategoryRule getOrCreateRule(UUID userId, String normalizedKey, String displayName,
                                          Set<Category> categories, Map<String, CategoryRule> batchCache) {
        CategoryRule cached = batchCache.get(normalizedKey);
        if (cached != null) {
            return cached;
        }

        Optional<CategoryRule> existing = categoryRuleRepository.findByUserIdAndMerchantKey(userId, normalizedKey);
        if (existing.isPresent()) {
            batchCache.put(normalizedKey, existing.get());
            return existing.get();
        }

        CategoryRule rule = new CategoryRule();
        rule.setUser(userRepository.getReferenceById(userId));
        rule.setMerchantKey(normalizedKey);
        rule.setDisplayName(displayName != null ? displayName : normalizedKey);
        rule.setCategories(categories);
        rule.setVerified(false);
        rule.setSource("LLM");
        rule.setAppliedCount(0);
        CategoryRule saved = categoryRuleRepository.save(rule);
        batchCache.put(normalizedKey, saved);
        return saved;
    }

    @Transactional
    public void verifyRule(CategoryRule rule) {
        rule.setVerified(true);
        categoryRuleRepository.save(rule);

        List<Transaction> txns = transactionRepository.findByAppliedRuleId(rule.getId());
        for (Transaction txn : txns) {
            if (txn.getReviewReasons() != null && txn.getReviewReasons().contains(ReviewReason.CATEGORY_UNVERIFIED)) {
                reviewStatusManager.clearReason(txn, ReviewReason.CATEGORY_UNVERIFIED, ReviewType.AUTO_REVIEWED);
                transactionRepository.save(txn);
            }
        }
    }

    @Transactional
    public void updateRuleCategories(CategoryRule rule, Set<Category> newCategories) {
        rule.setCategories(newCategories);
        categoryRuleRepository.save(rule);

        List<Transaction> txns = transactionRepository.findByAppliedRuleId(rule.getId());
        for (Transaction txn : txns) {
            if (txn.getReviewType() != ReviewType.MANUALLY_REVIEWED) {
                txn.setCategories(newCategories);
                transactionRepository.save(txn);
            }
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
        if (userCategories.isEmpty()) {
            self.applyCategorizationResults(txns, userCategories, userId, Map.of(), List.of(), List.of());
            return;
        }

        List<CategoryRule> rules = categoryRuleRepository.findByUserId(userId);

        Map<Integer, UUID> ruleMatchesByIndex = new HashMap<>();
        List<GeminiCategorizer.CategorizeItemRequest> llmBatchRequests = new ArrayList<>();

        for (int i = 0; i < txns.size(); i++) {
            Transaction txn = txns.get(i);
            if (txn.getDescription() == null || txn.getDescription().isBlank() || !txn.getCategories().isEmpty()) {
                continue;
            }

            Optional<CategoryRule> matchingRule = bestMatch(rules, txn.getDescription());
            if (matchingRule.isPresent()) {
                ruleMatchesByIndex.put(i, matchingRule.get().getId());
            } else {
                llmBatchRequests.add(new GeminiCategorizer.CategorizeItemRequest(i, txn.getDescription()));
            }
        }

        List<GeminiCategorizer.CategorizeItemResponse> llmResponses = List.of();
        if (!llmBatchRequests.isEmpty()) {
            List<String> categoryNames = userCategories.stream().map(Category::getName).toList();
            llmResponses = geminiCategorizer.categorize(llmBatchRequests, categoryNames);
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
                                            List<GeminiCategorizer.CategorizeItemRequest> llmBatchRequests,
                                            List<GeminiCategorizer.CategorizeItemResponse> llmResponses) {
        if (userCategories.isEmpty()) {
            log.info("User has zero categories. Flagging all transactions with CATEGORY_UNVERIFIED.");
            for (Transaction txn : txns) {
                if (txn.getDescription() != null && !txn.getDescription().isBlank() && txn.getCategories().isEmpty()) {
                    reviewStatusManager.addReason(txn, ReviewReason.CATEGORY_UNVERIFIED);
                }
            }
            saveAllTxnsIfPersisted(txns);
            return;
        }

        Map<String, CategoryRule> batchCache = new HashMap<>();

        for (Map.Entry<Integer, UUID> entry : ruleMatchesByIndex.entrySet()) {
            Transaction txn = txns.get(entry.getKey());
            CategoryRule rule = categoryRuleRepository.findWithCategoriesById(entry.getValue()).orElse(null);
            if (rule == null) {
                // Rule was deleted between the read-only matching pass and this transaction; fall back
                // to flagging for review rather than failing the whole batch.
                reviewStatusManager.addReason(txn, ReviewReason.CATEGORY_UNVERIFIED);
                continue;
            }

            txn.setCategories(rule.getCategories());
            txn.setAppliedRule(rule);
            rule.setAppliedCount(rule.getAppliedCount() + 1);
            rule.setLastAppliedAt(Instant.now());
            categoryRuleRepository.save(rule);

            if (!rule.isVerified()) {
                reviewStatusManager.addReason(txn, ReviewReason.CATEGORY_UNVERIFIED);
            }
        }

        if (!llmBatchRequests.isEmpty()) {
            for (GeminiCategorizer.CategorizeItemRequest req : llmBatchRequests) {
                Transaction txn = txns.get(req.index());

                GeminiCategorizer.CategorizeItemResponse res = llmResponses.stream()
                        .filter(r -> r.index() != null && r.index() == req.index())
                        .findFirst()
                        .orElse(null);

                boolean validResult = false;
                if (res != null) {
                    if (Boolean.TRUE.equals(res.noFit())) {
                        validResult = true;
                        reviewStatusManager.addReason(txn, ReviewReason.CATEGORY_UNVERIFIED);
                    } else if (res.categoryNames() != null && !res.categoryNames().isEmpty() && res.merchantKey() != null) {
                        Set<Category> resolvedCategories = new HashSet<>();
                        boolean catsValid = true;
                        for (String catName : res.categoryNames()) {
                            Category matchedCat = userCategories.stream()
                                    .filter(c -> c.getName().equalsIgnoreCase(catName))
                                    .findFirst()
                                    .orElse(null);
                            if (matchedCat == null) {
                                catsValid = false;
                                break;
                            }
                            resolvedCategories.add(matchedCat);
                        }

                        String normalizedKey = DescriptionNormalizer.normalize(res.merchantKey());
                        String normalizedDesc = DescriptionNormalizer.normalize(txn.getDescription());
                        boolean keyValid = normalizedKey.length() >= 3 && normalizedDesc.contains(normalizedKey);

                        if (catsValid && keyValid) {
                            validResult = true;
                            CategoryRule rule = getOrCreateRule(userId, normalizedKey, res.displayName(), resolvedCategories, batchCache);
                            txn.setCategories(resolvedCategories);
                            txn.setAppliedRule(rule);
                            rule.setAppliedCount(rule.getAppliedCount() + 1);
                            rule.setLastAppliedAt(Instant.now());
                            categoryRuleRepository.save(rule);

                            reviewStatusManager.addReason(txn, ReviewReason.CATEGORY_UNVERIFIED);
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
     * On-demand suggestion for a single free-text description, used by the /api/v1/categorize endpoint.
     * Never mutates anything and never throws - any failure (no rule/category match, LLM error, no fit)
     * simply resolves to an empty result.
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
            if (userCategories.isEmpty()) {
                return new SuggestionResult(Set.of(), null, false);
            }

            List<String> categoryNames = userCategories.stream().map(Category::getName).toList();
            List<GeminiCategorizer.CategorizeItemResponse> responses = geminiCategorizer.categorize(
                    List.of(new GeminiCategorizer.CategorizeItemRequest(0, description)), categoryNames);

            GeminiCategorizer.CategorizeItemResponse res = responses.stream().findFirst().orElse(null);
            if (res == null || Boolean.TRUE.equals(res.noFit()) || res.categoryNames() == null || res.categoryNames().isEmpty()) {
                return new SuggestionResult(Set.of(), null, false);
            }

            Set<Category> resolved = new HashSet<>();
            for (String catName : res.categoryNames()) {
                userCategories.stream()
                        .filter(c -> c.getName().equalsIgnoreCase(catName))
                        .findFirst()
                        .ifPresent(resolved::add);
            }

            if (resolved.isEmpty()) {
                return new SuggestionResult(Set.of(), null, false);
            }

            return new SuggestionResult(resolved, null, false);
        } catch (Exception e) {
            log.warn("Failed to compute categorization suggestion: {}", e.getMessage());
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
        return Optional.of(new SuggestionResult(categories, rule.getId(), true));
    }

    private void saveAllTxnsIfPersisted(List<Transaction> txns) {
        List<Transaction> toSave = txns.stream().filter(t -> t.getId() != null).toList();
        if (!toSave.isEmpty()) {
            transactionRepository.saveAll(toSave);
        }
    }
}
