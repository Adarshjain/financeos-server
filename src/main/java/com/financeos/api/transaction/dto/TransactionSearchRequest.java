package com.financeos.api.transaction.dto;

import com.financeos.domain.report.definition.FilterClause;

import java.util.List;

/**
 * Payload for transaction search and filter POST endpoint.
 */
public record TransactionSearchRequest(
        List<FilterClause> filters,
        String search
) {}
