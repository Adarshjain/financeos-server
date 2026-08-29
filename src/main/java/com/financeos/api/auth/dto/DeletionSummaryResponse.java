package com.financeos.api.auth.dto;

import java.util.Map;

public record DeletionSummaryResponse(
        Map<String, Long> counts,
        long total) {
}
