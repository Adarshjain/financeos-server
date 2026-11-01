package com.financeos.api.transaction.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.lang.Nullable;

import java.util.List;
import java.util.UUID;

/** Gives each of the transactions the user's pick of category; with {@code rule}, saves "do the same next time". */
public record ApplyUncategorizedRequest(
        @NotEmpty @Size(max = 10000) List<@NotNull UUID> transactionIds,
        @NotBlank String categoryKey,
        @Nullable String subcategoryKey,
        @Nullable @Valid Rule rule) {

    /** The rule to create (or re-point, when the user has one with this pattern and match type). */
    public record Rule(@NotBlank String pattern, @Nullable String matchType) {
    }
}
