package com.financeos.api.transaction.dto;

import com.financeos.domain.transaction.ReviewReason;
import com.financeos.domain.transaction.ReviewType;
import java.util.Set;
import java.util.UUID;

public record MergeTransactionsResponse(
    UUID keptId,
    ReviewType reviewType,
    Set<ReviewReason> remainingReasons
) {}
