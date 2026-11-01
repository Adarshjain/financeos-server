package com.financeos.api.transaction.dto;

import java.util.List;

/** How many of the user's transactions sit in each category and sub-category (whole ledger, unfiltered). */
public record CategoryCountsResponse(long uncategorized, List<CategoryCount> categories) {

    /** A category's total includes its sub-categories; {@code subs} lists only those with transactions. */
    public record CategoryCount(String categoryKey, long count, List<SubcategoryCount> subs) {
    }

    public record SubcategoryCount(String subcategoryKey, long count) {
    }
}
