package com.financeos.api.investment.dto;

import java.util.List;

public record ImportCommitResponse(
        int committed,
        int skipped,
        List<FailedCommitItem> failed
) {
    public record FailedCommitItem(
            int rowIndex,
            String reason
    ) {}
}
