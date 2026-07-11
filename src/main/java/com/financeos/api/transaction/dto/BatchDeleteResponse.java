package com.financeos.api.transaction.dto;

import java.util.List;

public record BatchDeleteResponse(
    List<String> succeededIds,
    List<BatchFailure> failures
) {}
