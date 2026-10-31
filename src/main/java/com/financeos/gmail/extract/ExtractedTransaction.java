package com.financeos.gmail.extract;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A transaction read out of an alert email by our own rules. Field meanings match the Gemini extractor:
 * {@code counterparty} is the merchant/payee/payer, {@code last4} the card or account tail. Fields the
 * email does not state are null.
 */
public record ExtractedTransaction(
        BigDecimal amount,
        String currency,
        Direction direction,
        LocalDate date,
        String counterparty,
        String last4,
        Tier tier,
        String templateId,
        double confidence
) {
    public static final double TEMPLATE_CONFIDENCE = 0.95;
    public static final double GENERIC_CONFIDENCE = 0.6;
}
