package com.financeos.domain.category;

import com.financeos.core.exception.ValidationException;
import com.financeos.domain.transaction.TransactionType;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The fixed global category list, loaded once and cached (it only changes with a migration).
 * Lookup by key, per-direction ordered lists, and the validation every category writer shares.
 */
@Component
public class CategoryCatalog {

    public record Sub(String key, String categoryKey, String name, CategoryDirection direction, int sortOrder) {
    }

    public record Cat(String key, String name, CategoryDirection direction, Integer outOrder, Integer inOrder,
                      String icon, boolean excludedByDefault, List<Sub> subs) {
    }

    private record Snapshot(Map<String, Cat> byKey, Map<String, Sub> subByKey, List<Cat> all,
                            List<Cat> out, List<Cat> in) {
    }

    /** The running application's catalog, for static DTO mappers that have no injection (see {@link #shared()}). */
    private static volatile CategoryCatalog shared;

    private final TxnCategoryRepository categoryRepository;
    private final TxnSubcategoryRepository subcategoryRepository;
    private volatile Snapshot snapshot;

    @org.springframework.beans.factory.annotation.Autowired
    public CategoryCatalog(TxnCategoryRepository categoryRepository, TxnSubcategoryRepository subcategoryRepository) {
        this.categoryRepository = categoryRepository;
        this.subcategoryRepository = subcategoryRepository;
        shared = this;
    }

    /**
     * The catalog the application uses; outside a Spring context (plain unit tests) one built from
     * taxonomy.json, which the DB seed mirrors.
     */
    public static CategoryCatalog shared() {
        CategoryCatalog c = shared;
        if (c == null) {
            TaxonomyFile file = TaxonomyFile.load();
            c = of(file.categories(), file.subcategories());
        }
        return c;
    }

    private CategoryCatalog(Snapshot snapshot) {
        this.categoryRepository = null;
        this.subcategoryRepository = null;
        this.snapshot = snapshot;
    }

    /** A catalog over the given rows, no database (tests). */
    public static CategoryCatalog of(List<TxnCategory> categories, List<TxnSubcategory> subcategories) {
        return new CategoryCatalog(build(categories, subcategories));
    }

    private Snapshot snapshot() {
        Snapshot s = snapshot;
        if (s == null) {
            synchronized (this) {
                s = snapshot;
                if (s == null) {
                    s = build(categoryRepository.findAll(), subcategoryRepository.findAll());
                    snapshot = s;
                }
            }
        }
        return s;
    }

    private static Snapshot build(List<TxnCategory> categories, List<TxnSubcategory> subcategories) {
        Map<String, List<Sub>> subsByCat = new LinkedHashMap<>();
        Map<String, Sub> subByKey = new LinkedHashMap<>();
        subcategories.stream()
                .sorted(Comparator.comparingInt(TxnSubcategory::getSortOrder).thenComparing(TxnSubcategory::getKey))
                .forEach(s -> {
                    Sub sub = new Sub(s.getKey(), s.getCategoryKey(), s.getName(), s.getDirection(), s.getSortOrder());
                    subByKey.put(sub.key(), sub);
                    subsByCat.computeIfAbsent(sub.categoryKey(), k -> new ArrayList<>()).add(sub);
                });
        Comparator<Integer> nullsLast = Comparator.nullsLast(Comparator.naturalOrder());
        List<Cat> all = categories.stream()
                .map(c -> new Cat(c.getKey(), c.getName(), c.getDirection(), c.getOutOrder(), c.getInOrder(),
                        c.getIcon(), c.isExcludedByDefault(), List.copyOf(subsByCat.getOrDefault(c.getKey(), List.of()))))
                .sorted(Comparator.comparing(Cat::outOrder, nullsLast).thenComparing(Cat::inOrder, nullsLast)
                        .thenComparing(Cat::key))
                .toList();
        Map<String, Cat> byKey = new LinkedHashMap<>();
        all.forEach(c -> byKey.put(c.key(), c));
        List<Cat> out = all.stream().filter(c -> c.direction().fits(CategoryDirection.OUT))
                .sorted(Comparator.comparing(Cat::outOrder, nullsLast).thenComparing(Cat::key)).toList();
        List<Cat> in = all.stream().filter(c -> c.direction().fits(CategoryDirection.IN))
                .sorted(Comparator.comparing(Cat::inOrder, nullsLast).thenComparing(Cat::key)).toList();
        return new Snapshot(Map.copyOf(byKey), Map.copyOf(subByKey), all, out, in);
    }

    public Optional<Cat> category(String key) {
        return key == null ? Optional.empty() : Optional.ofNullable(snapshot().byKey().get(key));
    }

    public Optional<Sub> subcategory(String key) {
        return key == null ? Optional.empty() : Optional.ofNullable(snapshot().subByKey().get(key));
    }

    /** Every category, in picker order of their first direction. */
    public List<Cat> all() {
        return snapshot().all();
    }

    /** The categories a transaction of this direction can take, in that direction's picker order. */
    public List<Cat> list(CategoryDirection direction) {
        return direction == CategoryDirection.IN ? snapshot().in()
                : direction == CategoryDirection.OUT ? snapshot().out() : snapshot().all();
    }

    /**
     * Checks that a category exists and the optional sub-category belongs to it, with no
     * transaction in play (a rule, say). A rule on an OUT-only category simply never fits credits.
     */
    public void validateKeys(String categoryKey, String subcategoryKey) {
        if (categoryKey == null) {
            throw new ValidationException("A category is required.");
        }
        Cat cat = category(categoryKey).orElseThrow(() -> new ValidationException("Unknown category: " + categoryKey));
        if (subcategoryKey != null) {
            Sub sub = subcategory(subcategoryKey)
                    .orElseThrow(() -> new ValidationException("Unknown sub-category: " + subcategoryKey));
            if (!sub.categoryKey().equals(categoryKey)) {
                throw new ValidationException("Sub-category " + sub.name() + " does not belong to " + cat.name() + ".");
            }
        }
    }

    /** True when {@link #validate} would accept this category for a transaction of the given type. */
    public boolean fits(TransactionType type, String categoryKey, String subcategoryKey) {
        try {
            validate(type, categoryKey, subcategoryKey);
            return true;
        } catch (ValidationException e) {
            return false;
        }
    }

    /**
     * Checks a category (and optional sub-category) for a transaction of the given type: both must
     * exist, the sub must belong to the category, and each direction must fit (DEBIT is OUT, CREDIT
     * is IN, BOTH fits either). A null category with a sub is rejected; both null is valid (clear).
     */
    public void validate(TransactionType type, String categoryKey, String subcategoryKey) {
        if (categoryKey == null) {
            if (subcategoryKey != null) {
                throw new ValidationException("A sub-category needs a category.");
            }
            return;
        }
        Cat cat = category(categoryKey).orElseThrow(() -> new ValidationException("Unknown category: " + categoryKey));
        CategoryDirection needed = CategoryDirection.of(type);
        if (!cat.direction().fits(needed)) {
            throw new ValidationException("Category " + cat.name() + " does not apply to "
                    + (needed == CategoryDirection.OUT ? "money out." : "money in."));
        }
        if (subcategoryKey != null) {
            Sub sub = subcategory(subcategoryKey)
                    .orElseThrow(() -> new ValidationException("Unknown sub-category: " + subcategoryKey));
            if (!sub.categoryKey().equals(categoryKey)) {
                throw new ValidationException("Sub-category " + sub.name() + " does not belong to " + cat.name() + ".");
            }
            if (!sub.direction().fits(needed)) {
                throw new ValidationException("Sub-category " + sub.name() + " does not apply to "
                        + (needed == CategoryDirection.OUT ? "money out." : "money in."));
            }
        }
    }
}
