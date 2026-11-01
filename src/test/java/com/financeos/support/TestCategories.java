package com.financeos.support;

import com.financeos.domain.categorization.CategoryRuleRepository;
import com.financeos.domain.category.CategoryCatalog;
import com.financeos.domain.category.TaxonomyFile;
import com.financeos.domain.transaction.TransactionCategoryWriter;

import static org.mockito.Mockito.mock;

/** The real category list (taxonomy.json) and writer for unit tests that need no database. */
public final class TestCategories {

    private TestCategories() {
    }

    public static CategoryCatalog catalog() {
        TaxonomyFile file = TaxonomyFile.load();
        return CategoryCatalog.of(file.categories(), file.subcategories());
    }

    /** A writer over the real list; rules resolve through the given repository (a mock by default). */
    public static TransactionCategoryWriter writer(CategoryRuleRepository ruleRepository) {
        return new TransactionCategoryWriter(catalog(), ruleRepository);
    }

    public static TransactionCategoryWriter writer() {
        return writer(mock(CategoryRuleRepository.class));
    }
}
