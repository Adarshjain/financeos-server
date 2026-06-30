package com.financeos.domain.transaction;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Represents the type of a transaction.
 * <p>
 * DEBIT: Money going out (expenses, charges, withdrawals)
 * CREDIT: Money coming in (income, payments, deposits)
 * <p>
 * Note: This is an internal representation. The API uses signed amounts
 * where negative = DEBIT and positive = CREDIT.
 */
public enum TransactionType {
    DEBIT,
    CREDIT;

    private static final Logger log = LoggerFactory.getLogger(TransactionType.class);

    /**
     * Common LLM-produced direction synonyms mapped to canonical enum values.
     */
    private static final Map<String, TransactionType> ALIASES = Map.ofEntries(
        Map.entry("DEBIT", DEBIT),       Map.entry("DR", DEBIT),
        Map.entry("CREDIT", CREDIT),     Map.entry("CR", CREDIT),
        Map.entry("PAYMENT", DEBIT),     Map.entry("EXPENSE", DEBIT),
        Map.entry("WITHDRAWAL", DEBIT),  Map.entry("CHARGE", DEBIT),
        Map.entry("INCOME", CREDIT),     Map.entry("DEPOSIT", CREDIT),
        Map.entry("REFUND", CREDIT)
    );

    /**
     * Lenient parser for LLM-produced direction strings.
     * <p>
     * Handles null/blank input and common synonyms (e.g. "DR", "PAYMENT").
     * Falls back to {@link #DEBIT} with a warning log for unrecognised values,
     * rather than crashing — the transaction is already tagged NEEDS_REVIEW
     * so a correctable direction is better than lost data.
     *
     * @param raw the direction string from Gemini (may be null, blank, or non-canonical)
     * @return the resolved {@link TransactionType}, never null
     */
    public static TransactionType fromLlmDirection(String raw) {
        if (raw == null || raw.isBlank()) {
            log.warn("LLM direction is null/blank — defaulting to DEBIT");
            return DEBIT;
        }
        TransactionType type = ALIASES.get(raw.trim().toUpperCase());
        if (type != null) {
            return type;
        }
        log.warn("Unrecognised LLM direction '{}' — defaulting to DEBIT", raw);
        return DEBIT;
    }
}
