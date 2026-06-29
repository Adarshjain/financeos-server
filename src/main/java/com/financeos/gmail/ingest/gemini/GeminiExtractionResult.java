package com.financeos.gmail.ingest.gemini;

import java.math.BigDecimal;
import java.time.LocalDate;

public record GeminiExtractionResult(
    boolean isTransaction,
    BigDecimal amount,
    String currency,
    String direction,
    LocalDate date,
    String description,
    String accountLast4,
    double confidence,
    boolean isSuccess,
    String failureReason
) {
    public static GeminiExtractionResult success(ExtractedTransaction tx) {
        return new GeminiExtractionResult(
            tx.isTransaction(),
            tx.amount(),
            tx.currency(),
            tx.direction(),
            tx.date(),
            tx.description(),
            tx.accountLast4(),
            tx.confidence(),
            true,
            null
        );
    }

    public static GeminiExtractionResult notTransaction() {
        return new GeminiExtractionResult(
            false, null, null, null, null, null, null, 0.0, true, null
        );
    }

    public static GeminiExtractionResult failure(String reason) {
        return new GeminiExtractionResult(
            false, null, null, null, null, null, null, 0.0, false, reason
        );
    }
}
