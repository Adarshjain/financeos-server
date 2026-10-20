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
        int duplicates,      // of those, how many were flagged DUPLICATE_SUSPECT
        /** Re-upload of a card statement that lacked figures: the fields filled (PERIOD_END, DUE_DATE, TOTAL_DUE, MINIMUM_DUE, or PERIOD_UPDATED when the stored period was replaced); empty = nothing new; null = not such a re-upload. */
        @Nullable List<String> filledDetails
    ) {
        public FileSummary(String filename, String status, int linesParsed, @Nullable String errorMessage,
                           @Nullable String warning, int created, int duplicates) {
            this(filename, status, linesParsed, errorMessage, warning, created, duplicates, null);
        }

        public FileSummary(String filename, String status, int linesParsed, String errorMessage) {
            this(filename, status, linesParsed, errorMessage, null, 0, 0, null);
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

