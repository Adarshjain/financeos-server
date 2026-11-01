package com.financeos.domain.categorization;

import com.financeos.domain.categorization.builtin.BuiltinCategorizer;
import com.financeos.domain.categorization.builtin.BuiltinMatch;
import com.financeos.domain.categorization.builtin.CategorizeInput;
import com.financeos.domain.transaction.CategorySource;
import com.financeos.domain.transaction.Transaction;
import com.financeos.domain.transaction.TransactionCategoryWriter;
import com.financeos.domain.transaction.TransactionRepository;
import com.financeos.domain.transaction.TransactionType;
import com.financeos.gmail.extract.Direction;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * "Who sets the category", in one place. The first step with an answer wins:
 * <ol>
 *   <li>the user's rules (most specific) - source RULE;</li>
 *   <li>links the transaction already has - source LINK;</li>
 *   <li>the built-in engine: keywords, known merchants, MCC - source AUTO;</li>
 *   <li>nothing: Uncategorized (a payment to a person stays here too).</li>
 * </ol>
 * {@link #assignOnArrival} runs this once when a transaction arrives and never again; editing never re-runs it.
 * {@link #recategorise} re-runs it for rows that lost the thing that set their category. A USER category is
 * never touched. Everything is batch-friendly: one rules query per user and a few link queries per call.
 */
@Component
public class CategoryAssigner {

    private final CategoryRuleRepository ruleRepository;
    private final LinkCategories linkCategories;
    private final BuiltinCategorizer engine;
    private final TransactionCategoryWriter writer;
    private final TransactionRepository transactionRepository;

    public CategoryAssigner(CategoryRuleRepository ruleRepository, LinkCategories linkCategories,
                            BuiltinCategorizer engine, TransactionCategoryWriter writer,
                            TransactionRepository transactionRepository) {
        this.ruleRepository = ruleRepository;
        this.linkCategories = linkCategories;
        this.engine = engine;
        this.writer = writer;
        this.transactionRepository = transactionRepository;
    }

    /** A suggestion for text typed in a form: from a rule or from the engine. */
    public record Suggestion(String categoryKey, String subcategoryKey, CategorySource source) {
    }

    /**
     * Arrival: gives each row that has no category yet the first answer of rules, links, engine. Rows
     * that already have a category (the caller's pick, a USER category) are left alone. Changes the entities in
     * place and does not save: callers persist them (new rows are persisted right after). Returns how many got one.
     */
    public int assignOnArrival(List<Transaction> txns) {
        return run(txns, Mode.ARRIVAL, true, null).size();
    }

    /** {@link #assignOnArrival} for rows already saved (a Gmail sync batch): loads them by id for the user and saves. */
    @Transactional
    public int assignOnArrivalById(UUID userId, Collection<UUID> ids) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        List<Transaction> rows = new ArrayList<>();
        List<UUID> all = new ArrayList<>(ids);
        for (int from = 0; from < all.size(); from += 500) {
            rows.addAll(transactionRepository.findAllByIdInAndUserId(all.subList(from, Math.min(from + 500, all.size())), userId));
        }
        List<Transaction> changed = run(rows, Mode.ARRIVAL, true, null);
        if (!changed.isEmpty()) {
            transactionRepository.saveAll(changed);
        }
        return changed.size();
    }

    /**
     * Backfill: like arrival for rows already stored, but is_excluded is left exactly as it is. Changes the
     * entities in place; the caller saves the returned rows.
     */
    public List<Transaction> backfill(List<Transaction> txns) {
        return run(txns, Mode.BACKFILL, false, null);
    }

    /**
     * Re-runs rules, links and the engine for rows that are not USER's; a row nothing answers for loses its
     * category. {@code excludeRuleId} is a rule being deleted (still stored). Returns the rows that changed;
     * the caller saves them.
     */
    public List<Transaction> recategorise(List<Transaction> txns, UUID excludeRuleId) {
        return run(txns, Mode.RECATEGORISE, true, excludeRuleId);
    }

    /** What rules and then the engine say about a description typed in a form (no transaction yet). */
    @Transactional(readOnly = true)
    public Optional<Suggestion> suggest(UUID userId, String description, TransactionType type) {
        if (description == null || description.isBlank()) {
            return Optional.empty();
        }
        RuleMatcher.MatchContext ctx = RuleMatcher.MatchContext.of(description);
        if (!ctx.isBlank()) {
            Optional<CategoryRule> rule = ruleRepository.findByUserId(userId).stream()
                    .filter(r -> RuleMatcher.matches(r, ctx))
                    .filter(r -> type == null || writer.fitsType(type, r.getCategoryKey(), r.getSubcategoryKey()))
                    .max(CategorizationService.RULE_PRECEDENCE);
            if (rule.isPresent()) {
                return Optional.of(new Suggestion(rule.get().getCategoryKey(), rule.get().getSubcategoryKey(), CategorySource.RULE));
            }
        }
        BuiltinMatch m = engine.categorize(engineInput(description, type, null, null));
        return m.hasCategory()
                ? Optional.of(new Suggestion(m.categoryKey(), m.subcategoryKey(), CategorySource.AUTO))
                : Optional.empty();
    }

    // ------------------------------------------------------------------ core

    private enum Mode { ARRIVAL, BACKFILL, RECATEGORISE }

    private List<Transaction> run(List<Transaction> txns, Mode mode, boolean touchExclusion, UUID excludeRuleId) {
        List<Transaction> todo = new ArrayList<>();
        if (txns != null) {
            for (Transaction t : txns) {
                if (t.getCategorySource() == CategorySource.USER) {
                    continue;
                }
                if (mode != Mode.RECATEGORISE && t.getCategoryKey() != null) {
                    continue;
                }
                todo.add(t);
            }
        }
        if (todo.isEmpty()) {
            return List.of();
        }
        Map<UUID, List<CategoryRule>> rulesByUser = new HashMap<>();
        for (Transaction t : todo) {
            UUID userId = userId(t);
            if (userId != null && !rulesByUser.containsKey(userId)) {
                List<CategoryRule> rules = ruleRepository.findByUserId(userId);
                if (excludeRuleId != null) {
                    rules = rules.stream().filter(r -> !excludeRuleId.equals(r.getId())).toList();
                }
                rulesByUser.put(userId, rules);
            }
        }
        Map<UUID, LinkCategories.Target> links = linkCategories.targetsFor(todo, true);

        List<Transaction> changed = new ArrayList<>();
        for (Transaction t : todo) {
            String cat = null;
            String sub = null;
            CategorySource source = null;
            CategoryRule rule = bestRule(rulesByUser.getOrDefault(userId(t), List.of()), t);
            if (rule != null) {
                cat = rule.getCategoryKey();
                sub = rule.getSubcategoryKey();
                source = CategorySource.RULE;
            } else {
                LinkCategories.Target link = t.getId() == null ? null : links.get(t.getId());
                if (link != null) {
                    cat = link.categoryKey();
                    sub = link.subcategoryKey();
                    source = CategorySource.LINK;
                } else {
                    BuiltinMatch m = engine.categorize(engineInput(text(t), t.getType(), t.getAmount(), t.getMcc()));
                    if (m.hasCategory() && writer.fits(t, m.categoryKey(), m.subcategoryKey())) {
                        cat = m.categoryKey();
                        sub = m.subcategoryKey();
                        source = CategorySource.AUTO;
                    }
                }
            }
            if (source == null) {
                if (mode == Mode.RECATEGORISE && t.getCategoryKey() != null
                        && writer.set(t, null, null, CategorySource.AUTO, (CategoryRule) null, touchExclusion)) {
                    changed.add(t);
                }
                continue;
            }
            if (sameAs(t, cat, sub, source, rule)) {
                continue;
            }
            if (writer.set(t, cat, sub, source, rule, touchExclusion)) {
                changed.add(t);
            }
        }
        return changed;
    }

    private static boolean sameAs(Transaction t, String cat, String sub, CategorySource source, CategoryRule rule) {
        return Objects.equals(t.getCategoryKey(), cat) && Objects.equals(t.getSubcategoryKey(), sub)
                && t.getCategorySource() == source
                && (source != CategorySource.RULE || (t.getAppliedRule() != null && rule != null
                && Objects.equals(t.getAppliedRule().getId(), rule.getId())));
    }

    private CategoryRule bestRule(List<CategoryRule> rules, Transaction t) {
        if (rules.isEmpty()) {
            return null;
        }
        RuleMatcher.MatchContext ctx = RuleMatcher.MatchContext.of(text(t));
        if (ctx.isBlank()) {
            return null;
        }
        return rules.stream()
                .filter(r -> RuleMatcher.matches(r, ctx))
                .filter(r -> writer.fits(t, r.getCategoryKey(), r.getSubcategoryKey()))
                .max(CategorizationService.RULE_PRECEDENCE)
                .orElse(null);
    }

    private static UUID userId(Transaction t) {
        return t.getUser() == null ? null : t.getUser().getId();
    }

    /** The statement or alert text of the row (what rules match on), else the user's own description. */
    private static String text(Transaction t) {
        return t.getSourcedDescription() != null && !t.getSourcedDescription().isBlank()
                ? t.getSourcedDescription() : t.getDescription();
    }

    /** The description plays both counterparty and text: a statement line has no separate mail around it. */
    private static CategorizeInput engineInput(String description, TransactionType type, java.math.BigDecimal amount, String mcc) {
        Direction direction = type == null ? null : type == TransactionType.CREDIT ? Direction.CREDIT : Direction.DEBIT;
        return new CategorizeInput(description, direction, amount, null, null, description, null, mcc);
    }
}
