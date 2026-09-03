package com.financeos.api.transaction.dto;

import com.financeos.domain.transaction.ReviewReason;
import com.financeos.domain.transaction.ReviewType;
import org.springframework.lang.Nullable;
import java.util.Set;
import java.util.UUID;

public record MergeTransactionsResponse(
    UUID keptId,
    @Nullable ReviewType reviewType,
    Set<ReviewReason> remainingReasons
) {}
