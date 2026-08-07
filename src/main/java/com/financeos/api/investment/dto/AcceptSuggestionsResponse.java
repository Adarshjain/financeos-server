package com.financeos.api.investment.dto;

import java.util.List;

public record AcceptSuggestionsResponse(
        List<DividendResponse> created,
        int skippedCount
) {}
