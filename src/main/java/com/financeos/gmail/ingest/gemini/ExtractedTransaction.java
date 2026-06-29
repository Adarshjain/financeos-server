package com.financeos.gmail.ingest.gemini;

import java.math.BigDecimal;
import java.time.LocalDate;

public record ExtractedTransaction(
    boolean isTransaction,
    BigDecimal amount,
    String currency,
    String direction, // "DEBIT" or "CREDIT"
    LocalDate date,
    String description,
    String accountLast4,
    double confidence
) {}
