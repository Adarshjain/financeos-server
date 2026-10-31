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
    String failureReason,
    LocalDate dateCorrectedFrom,
    String provider
) {
    /**
     * @param dateCorrectedFrom the date the model returned when it was later than the day the
     *                          email was sent and {@code date} was capped to the sent day; null
     *                          when the model's date was kept
     */
    public GeminiExtractionResult(boolean isTransaction, BigDecimal amount, String currency, String direction,
                                  LocalDate date, String description, String accountLast4, double confidence,
                                  boolean isSuccess, String failureReason) {
        this(isTransaction, amount, currency, direction, date, description, accountLast4, confidence,
                isSuccess, failureReason, null, null);
    }

    public GeminiExtractionResult(boolean isTransaction, BigDecimal amount, String currency, String direction,
                                  LocalDate date, String description, String accountLast4, double confidence,
                                  boolean isSuccess, String failureReason, LocalDate dateCorrectedFrom) {
        this(isTransaction, amount, currency, direction, date, description, accountLast4, confidence,
                isSuccess, failureReason, dateCorrectedFrom, null);
    }

    /** The same result tagged with the provider/model that produced it ("providerId/model"); shadow comparison only. */
    public GeminiExtractionResult withProvider(String provider) {
        return new GeminiExtractionResult(isTransaction, amount, currency, direction, date, description,
                accountLast4, confidence, isSuccess, failureReason, dateCorrectedFrom, provider);
    }

    public boolean dateCorrected() {
        return dateCorrectedFrom != null;
    }

    /** The same result with {@code date} capped to {@code sentOn}; {@code dateCorrectedFrom} keeps the model's date. */
    public GeminiExtractionResult withDateCappedTo(LocalDate sentOn) {
        return new GeminiExtractionResult(isTransaction, amount, currency, direction, sentOn, description,
                accountLast4, confidence, isSuccess, failureReason, date, provider);
    }

    public static GeminiExtractionResult success(ExtractedTransaction tx, LocalDate parsedDate) {
        return new GeminiExtractionResult(
            tx.isTransaction(),
            tx.amount(),
            tx.currency(),
            tx.direction(),
            parsedDate,
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
