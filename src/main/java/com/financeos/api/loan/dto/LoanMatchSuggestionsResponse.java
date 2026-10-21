package com.financeos.api.loan.dto;

import java.util.List;
import java.util.UUID;

public record LoanMatchSuggestionsResponse(
        UUID loanId,
        String loanName,
        List<MatchSuggestionsResponse.InstallmentMatchSuggestion> suggestions
) {}
