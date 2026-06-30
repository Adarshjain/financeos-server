package com.financeos.domain.transaction;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class TransactionTypeFromLlmDirectionTest {

    // --- Canonical values ---

    @Test
    void canonicalDebit() {
        assertEquals(TransactionType.DEBIT, TransactionType.fromLlmDirection("DEBIT"));
    }

    @Test
    void canonicalCredit() {
        assertEquals(TransactionType.CREDIT, TransactionType.fromLlmDirection("CREDIT"));
    }

    // --- Case insensitivity ---

    @Test
    void caseInsensitiveDebit() {
        assertEquals(TransactionType.DEBIT, TransactionType.fromLlmDirection("debit"));
    }

    @Test
    void caseInsensitiveCredit() {
        assertEquals(TransactionType.CREDIT, TransactionType.fromLlmDirection("Credit"));
    }

    @Test
    void mixedCaseAlias() {
        assertEquals(TransactionType.DEBIT, TransactionType.fromLlmDirection("Dr"));
    }

    // --- Aliases → DEBIT ---

    @ParameterizedTest
    @ValueSource(strings = {"DR", "PAYMENT", "EXPENSE", "WITHDRAWAL", "CHARGE"})
    void debitAliases(String alias) {
        assertEquals(TransactionType.DEBIT, TransactionType.fromLlmDirection(alias));
    }

    // --- Aliases → CREDIT ---

    @ParameterizedTest
    @ValueSource(strings = {"CR", "INCOME", "DEPOSIT", "REFUND"})
    void creditAliases(String alias) {
        assertEquals(TransactionType.CREDIT, TransactionType.fromLlmDirection(alias));
    }

    // --- Null and blank → DEBIT fallback ---

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  ", "\t", "\n"})
    void nullAndBlankDefaultToDebit(String input) {
        assertEquals(TransactionType.DEBIT, TransactionType.fromLlmDirection(input));
    }

    // --- Unrecognised values → DEBIT fallback (no exception) ---

    @ParameterizedTest
    @ValueSource(strings = {"TRANSFER", "UNKNOWN", "xyz123", "DEB1T"})
    void unrecognisedValuesDefaultToDebit(String input) {
        assertEquals(TransactionType.DEBIT, TransactionType.fromLlmDirection(input));
    }

    // --- Whitespace trimming ---

    @Test
    void leadingTrailingWhitespace() {
        assertEquals(TransactionType.CREDIT, TransactionType.fromLlmDirection("  CREDIT  "));
    }

    // --- Never returns null ---

    @Test
    void neverReturnsNull() {
        assertNotNull(TransactionType.fromLlmDirection(null));
        assertNotNull(TransactionType.fromLlmDirection(""));
        assertNotNull(TransactionType.fromLlmDirection("GARBAGE"));
    }
}
