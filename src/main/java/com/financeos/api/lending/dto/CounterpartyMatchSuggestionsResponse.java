package com.financeos.api.lending.dto;

import java.util.List;
import java.util.UUID;

/** A person with at least one entry that has bank-transaction candidates. */
public record CounterpartyMatchSuggestionsResponse(
        UUID counterpartyId,
        String counterpartyName,
        List<LendingMatchSuggestionsResponse.LendingMatchSuggestion> suggestions
) {
}
