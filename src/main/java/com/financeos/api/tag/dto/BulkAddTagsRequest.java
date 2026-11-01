package com.financeos.api.tag.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

public record BulkAddTagsRequest(
        @NotEmpty(message = "Transaction IDs list cannot be empty")
        @Size(max = 5000, message = "Transaction IDs list cannot exceed 5000 elements")
        List<@NotNull(message = "Transaction ID cannot be null") UUID> transactionIds,

        @NotEmpty(message = "Tag IDs list cannot be empty")
        @Size(max = 50, message = "Tag IDs list cannot exceed 50 elements")
        List<@NotNull(message = "Tag ID cannot be null") UUID> tagIds) {
}
