package com.financeos.api.transaction.dto;

import com.financeos.api.category.dto.CategoryRef;
import com.financeos.api.category.dto.SubcategoryRef;
import com.financeos.domain.category.CategoryCatalog;
import com.financeos.domain.transaction.CategorySource;
import com.financeos.domain.transaction.Transaction;
import com.financeos.domain.transaction.TransactionService;
import org.springframework.lang.Nullable;

import java.time.Instant;
import java.util.UUID;

/** The transaction's category fields after the change, and the ones before it (feed them to restore for Undo). */
public record SetCategoryResponse(
        UUID transactionId,
        @Nullable CategoryRef category,
        @Nullable SubcategoryRef subcategory,
        @Nullable CategorySource categorySource,
        @Nullable UUID categoryRuleId,
        @Nullable Instant categorySetAt,
        boolean isTransactionExcluded,
        Previous previous) {

    public record Previous(
            @Nullable String categoryKey,
            @Nullable String subcategoryKey,
            @Nullable CategorySource source,
            @Nullable UUID ruleId,
            @Nullable Instant setAt) {
    }

    public static SetCategoryResponse from(TransactionService.CategoryChange change, CategoryCatalog catalog) {
        Transaction t = change.transaction();
        return new SetCategoryResponse(
                t.getId(),
                CategoryRef.of(catalog, t.getCategoryKey()),
                SubcategoryRef.of(catalog, t.getSubcategoryKey()),
                t.getCategorySource(),
                t.getAppliedRule() != null ? t.getAppliedRule().getId() : null,
                t.getCategorySetAt(),
                t.isTransactionExcluded(),
                new Previous(change.previousCategoryKey(), change.previousSubcategoryKey(), change.previousSource(),
                        change.previousRuleId(), change.previousSetAt()));
    }
}
