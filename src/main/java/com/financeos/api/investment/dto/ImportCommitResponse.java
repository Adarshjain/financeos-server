package com.financeos.api.investment.dto;

import org.springframework.lang.Nullable;

import java.util.List;

public record ImportCommitResponse(
        int committed,
        int skipped,
        List<FailedCommitItem> failed,
        List<SkippedCommitItem> skippedItems
) {
    public record FailedCommitItem(int rowIndex, @Nullable String scrip, @Nullable String reason) {}
    public record SkippedCommitItem(int rowIndex, @Nullable String scrip, String reason) {}
}
