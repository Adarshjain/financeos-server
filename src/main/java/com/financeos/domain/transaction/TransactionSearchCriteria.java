package com.financeos.domain.transaction;

import com.financeos.domain.report.definition.FilterClause;
import java.util.List;

/**
 * Internal search criteria for transactions.
 */
public record TransactionSearchCriteria(
        List<FilterClause> filters,
        String search
) {}
