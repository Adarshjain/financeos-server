package com.financeos.api.transaction.dto;

import java.util.List;

public record BatchReviewResponse(
    List<String> succeededIds,
    List<String> skippedIds,
    List<BatchFailure> failures
) {}
