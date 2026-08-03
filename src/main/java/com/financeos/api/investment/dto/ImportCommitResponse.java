package com.financeos.api.investment.dto;

import java.util.List;

public record ImportCommitResponse(
        int committed,
        int skipped,
        List<FailedCommitItem> failed,
        List<SkippedCommitItem> skippedItems
) {
    public record FailedCommitItem(int rowIndex, String scrip, String reason) {}
    public record SkippedCommitItem(int rowIndex, String scrip, String reason) {}
}
