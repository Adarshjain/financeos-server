package com.financeos.api.categorize.dto;

import com.financeos.domain.transaction.TransactionType;
import jakarta.validation.constraints.NotBlank;

/**
 * {@code type} (optional) is the direction of the transaction being typed: with it a suggestion that cannot
 * apply to money in (or out) is not offered.
 */
public record CategorizeRequest(@NotBlank String description, TransactionType type) {}
