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
        String status, // "SUCCESS", "FAILED", "SKIPPED", "HELD"
        int linesParsed,
        @Nullable String errorMessage, // FAILED reason or SKIPPED reason ONLY
        @Nullable String warning,      // e.g. account-number-mismatch warning on a SUCCESS file
        int created,         // txns inserted from this file
        int duplicates,      // of those, how many were flagged DUPLICATE_SUSPECT
        /** Re-upload of a card statement that lacked figures: the fields filled (PERIOD_END, DUE_DATE, TOTAL_DUE, MINIMUM_DUE, or PERIOD_UPDATED when the stored period was replaced); empty = nothing new; null = not such a re-upload. */
        @Nullable List<String> filledDetails,
        /** Why a FAILED file failed, for the client to branch on; null on success, skipped and held files. */
        @Nullable ErrorCode errorCode,
        /** The held upload of a HELD file (waiting for an account); null otherwise. */
        @Nullable UUID heldUploadId
    ) {
        public FileSummary(String filename, String status, int linesParsed, @Nullable String errorMessage,
                           @Nullable String warning, int created, int duplicates, @Nullable List<String> filledDetails) {
            this(filename, status, linesParsed, errorMessage, warning, created, duplicates, filledDetails, null, null);
        }

        public FileSummary(String filename, String status, int linesParsed, @Nullable String errorMessage,
                           @Nullable String warning, int created, int duplicates) {
            this(filename, status, linesParsed, errorMessage, warning, created, duplicates, null, null, null);
        }

        public FileSummary(String filename, String status, int linesParsed, String errorMessage) {
            this(filename, status, linesParsed, errorMessage, null, 0, 0, null, null, null);
        }

        /** A failed file with its reason code. */
        public static FileSummary failed(String filename, String errorMessage, ErrorCode code) {
            return new FileSummary(filename, "FAILED", 0, errorMessage, null, 0, 0, null, code, null);
        }

        /** A file kept aside until the user picks its account. */
        public static FileSummary held(String filename, String message, UUID heldUploadId) {
            return new FileSummary(filename, "HELD", 0, message, null, 0, 0, null, null, heldUploadId);
        }

        public FileSummary withCounts(int created, int duplicates) {
            return new FileSummary(filename, status, linesParsed, errorMessage, warning, created, duplicates, filledDetails, errorCode, heldUploadId);
        }
    }

    /** Why a file could not be read: a locked file needs a password, a wrong one needs another, the rest cannot be fixed by the user. */
    public enum ErrorCode {
        PASSWORD_REQUIRED, WRONG_PASSWORD, UNREADABLE, OTHER;

        /** Maps a parser failure message; {@code passwordTried} says whether a password was supplied for the attempt. */
        public static ErrorCode classify(@Nullable String message, boolean passwordTried) {
            if (com.financeos.statement.parser.StatementParseException.isPasswordMessage(message)) {
                return passwordTried ? WRONG_PASSWORD : PASSWORD_REQUIRED;
            }
            if (message == null) {
                return OTHER;
            }
            String m = message.toLowerCase(java.util.Locale.ROOT);
            if (m.startsWith("error: failed to read") || m.startsWith("error: no text layer") || m.startsWith("error: too many pages") || m.contains("file is empty")
                    || m.contains("unsupported") || m.startsWith("error: cannot read") || m.startsWith("error: file too small")) {
                return UNREADABLE;
            }
            return OTHER;
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

