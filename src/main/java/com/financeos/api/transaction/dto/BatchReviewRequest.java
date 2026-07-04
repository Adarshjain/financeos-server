package com.financeos.api.transaction.dto;

import com.financeos.domain.transaction.ReviewType;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

public record BatchReviewRequest(
    @NotEmpty(message = "Transaction IDs list cannot be empty")
    @Size(max = 500, message = "Transaction IDs list cannot exceed 500 elements")
    List<@NotNull(message = "Transaction ID cannot be null") UUID> transactionIds,

    @NotNull(message = "Review type is required")
    ReviewType reviewType
) {}
