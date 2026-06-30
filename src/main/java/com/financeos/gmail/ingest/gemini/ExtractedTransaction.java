package com.financeos.gmail.ingest.gemini;

import java.math.BigDecimal;

public record ExtractedTransaction(
    boolean isTransaction,
    BigDecimal amount,
    String currency,
    String direction, // "DEBIT" or "CREDIT"
    String date,      // raw date string from Gemini; parsed by FlexibleDateParser
    String description,
    String accountLast4,
    double confidence
) {}
