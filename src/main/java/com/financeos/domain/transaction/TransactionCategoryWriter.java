package com.financeos.domain.transaction;

import com.financeos.core.time.AppTime;
import com.financeos.domain.categorization.CategoryRule;
import com.financeos.domain.categorization.CategoryRuleRepository;
import com.financeos.domain.category.CategoryCatalog;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The one place a transaction's category is written: rules, links, the engine and the user all
 * come through here. It validates against {@link CategoryCatalog}, stamps the source and time, and
 * keeps is_excluded following the category's default until the user has chosen it by hand.
 * The caller saves the transaction.
 */
@Component
public class TransactionCategoryWriter {

    private final CategoryCatalog catalog;
    private final CategoryRuleRepository ruleRepository;

    public TransactionCategoryWriter(CategoryCatalog catalog, CategoryRuleRepository ruleRepository) {
        this.catalog = catalog;
        this.ruleRepository = ruleRepository;
    }

    /**
     * Sets the category/sub-category (both null clears it) from {@code source}, stamped now. A source
     * other than USER never overwrites a USER category: returns false and changes nothing. {@code ruleId}
     * is kept as applied_rule_id only for source RULE (and only if it is the transaction owner's rule).
     */
    public boolean set(Transaction txn, String categoryKey, String subcategoryKey, CategorySource source, UUID ruleId) {
        return set(txn, categoryKey, subcategoryKey, source, resolveRule(txn, source, ruleId));
    }

    /** As {@link #set(Transaction, String, String, CategorySource, UUID)} with the rule already loaded (bulk callers). */
    public boolean set(Transaction txn, String categoryKey, String subcategoryKey, CategorySource source,
                       CategoryRule rule) {
        return set(txn, categoryKey, subcategoryKey, source, rule, true);
    }

    /**
     * As above; with {@code touchExclusion} false the row's is_excluded is left exactly as it is (the
     * backfill of pre-existing rows keeps today's totals).
     */
    public boolean set(Transaction txn, String categoryKey, String subcategoryKey, CategorySource source,
                       CategoryRule rule, boolean touchExclusion) {
        Objects.requireNonNull(source, "source");
        if (source != CategorySource.USER && txn.getCategorySource() == CategorySource.USER) {
            return false;
        }
        apply(txn, categoryKey, subcategoryKey, source, rule, AppTime.nowInstant(), touchExclusion);
        return true;
    }

    /** True when this category/sub-category is valid for the transaction's direction (see {@link CategoryCatalog#validate}). */
    public boolean fits(Transaction txn, String categoryKey, String subcategoryKey) {
        return catalog.fits(txn.getType(), categoryKey, subcategoryKey);
    }

    /** As {@link #fits(Transaction, String, String)} for a type with no transaction yet (a suggestion). */
    public boolean fitsType(TransactionType type, String categoryKey, String subcategoryKey) {
        return catalog.fits(type, categoryKey, subcategoryKey);
    }

    /**
     * Puts back a category state a user is undoing to (their own action, so it may overwrite USER):
     * the given source, rule and time, or no category at all when {@code source} is null.
     */
    public void restore(Transaction txn, String categoryKey, String subcategoryKey, CategorySource source,
                        UUID ruleId, Instant setAt) {
        apply(txn, categoryKey, subcategoryKey, source, resolveRule(txn, source, ruleId), setAt, true);
    }

    private void apply(Transaction txn, String categoryKey, String subcategoryKey, CategorySource source,
                       CategoryRule rule, Instant setAt, boolean touchExclusion) {
        if (source == CategorySource.LINK && categoryKey != null) {
            // A refund keeps the original purchase's category, so the direction need not fit.
            catalog.validateKeys(categoryKey, subcategoryKey);
        } else {
            catalog.validate(txn.getType(), categoryKey, subcategoryKey);
        }
        if (categoryKey == null) {
            // Cleared: only the user's own clear is a decision worth remembering.
            txn.setCategoryKey(null);
            txn.setSubcategoryKey(null);
            txn.setCategorySource(source == CategorySource.USER ? CategorySource.USER : null);
            txn.setCategorySetAt(source == CategorySource.USER ? setAt : null);
            txn.setAppliedRule(null);
        } else {
            txn.setCategoryKey(categoryKey);
            txn.setSubcategoryKey(subcategoryKey);
            txn.setCategorySource(source);
            txn.setCategorySetAt(source == null ? null : setAt);
            txn.setAppliedRule(source == CategorySource.RULE ? rule : null);
        }
        if (touchExclusion && !txn.isExcludeSetByUser()) {
            txn.setTransactionExcluded(categoryKey != null
                    && catalog.category(categoryKey).map(CategoryCatalog.Cat::excludedByDefault).orElse(false));
        }
    }

    private CategoryRule resolveRule(Transaction txn, CategorySource source, UUID ruleId) {
        if (source != CategorySource.RULE || ruleId == null || txn.getUser() == null) {
            return null;
        }
        List<CategoryRule> found = ruleRepository.findByUserIdAndIdIn(txn.getUser().getId(), List.of(ruleId));
        return found.isEmpty() ? null : found.get(0);
    }
}
