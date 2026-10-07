package com.financeos.api.lending.dto;

import org.springframework.lang.Nullable;

/** Best name match for a transaction description, or {@code counterparty = null} when nothing overlaps. */
public record CounterpartySuggestionResponse(
        @Nullable CounterpartyResponse counterparty
) {}
