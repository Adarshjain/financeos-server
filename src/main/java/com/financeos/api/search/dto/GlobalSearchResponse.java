package com.financeos.api.search.dto;

import java.util.List;

/** Up to 5 matches per group for the current user; every group is always present (empty when nothing matches). */
public record GlobalSearchResponse(
        List<SearchAccountHit> accounts,
        List<SearchTransactionHit> transactions,
        List<SearchHoldingHit> holdings,
        List<SearchPersonHit> people,
        List<SearchLoanHit> loans,
        List<SearchReportHit> reports,
        List<SearchDashboardHit> dashboards) {
}
