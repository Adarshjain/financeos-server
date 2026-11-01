package com.financeos.api.transaction.dto;

/** Whether the user has any uncategorized transaction (what {@code summary.count > 0} says, without the scan). */
public record UncategorizedExistsResponse(boolean exists) {
}
