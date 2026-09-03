package com.financeos.api.transaction.dto;

import com.financeos.domain.report.definition.FilterClause;
import org.springframework.lang.Nullable;

import java.util.List;

/**
 * Payload for transaction search and filter POST endpoint.
 */
public record TransactionSearchRequest(
        @Nullable List<FilterClause> filters,
        @Nullable String search
) {}

