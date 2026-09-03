package com.financeos.domain.ingestion;

import org.springframework.lang.Nullable;

import java.util.List;
import java.util.UUID;
import java.math.BigDecimal;

public record FileIngestionResult(
    int filesProcessed,
    int totalCreated,
    int totalDuplicatesFound,
    List<FileSummary> fileDetails,
    List<DuplicateDetail> duplicateDetails,
    int duplicatesTruncated
) {
    public FileIngestionResult(int filesProcessed, int totalCreated, int totalDuplicatesFound, List<FileSummary> fileDetails) {
        this(filesProcessed, totalCreated, totalDuplicatesFound, fileDetails, List.of(), 0);
    }

    public record FileSummary(
        String filename,
        String status, // "SUCCESS", "FAILED", "SKIPPED"
        int linesParsed,
        @Nullable String errorMessage, // FAILED reason or SKIPPED reason ONLY
        @Nullable String warning,      // e.g. account-number-mismatch warning on a SUCCESS file
        int created,         // txns inserted from this file
        int duplicates       // of those, how many were flagged DUPLICATE_SUSPECT
    ) {
        public FileSummary(String filename, String status, int linesParsed, String errorMessage) {
            this(filename, status, linesParsed, errorMessage, null, 0, 0);
        }
    }

    public record DuplicateDetail(
        String date,
        BigDecimal amount,
        String description,
        String filename,
        UUID transactionId,
        UUID matchedTransactionId
    ) {}
}

