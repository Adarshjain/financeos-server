package com.financeos.domain.lending;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class UnrecordedTransferMatcherTest {

    @Test
    void matchesAWholeWordCaseInsensitively() {
        assertTrue(UnrecordedTransferMatcher.matches("Rahul", "UPI/DR/123/RAHUL/okaxis"));
        assertTrue(UnrecordedTransferMatcher.matches("rahul", "Sent to Rahul"));
    }

    @Test
    void doesNotMatchInsideALongerWord() {
        assertFalse(UnrecordedTransferMatcher.matches("Dad", "DADAR RAILWAY"));
        assertFalse(UnrecordedTransferMatcher.matches("Asha", "Ashanti stores"));
        assertTrue(UnrecordedTransferMatcher.matches("Dad", "UPI-DAD-HOME"));
    }

    @Test
    void everyLongTokenMustAppear() {
        assertTrue(UnrecordedTransferMatcher.matches("Rahul Sharma", "NEFT Sharma, Rahul"));
        assertFalse(UnrecordedTransferMatcher.matches("Rahul Sharma", "UPI Rahul Verma"));
    }

    @Test
    void shortTokensAreIgnoredAndAllShortNamesNeverMatch() {
        assertTrue(UnrecordedTransferMatcher.matches("Rahul K", "UPI Rahul"));
        assertFalse(UnrecordedTransferMatcher.matches("Jo", "Jo paid"));
        assertFalse(UnrecordedTransferMatcher.matches("A B", "A B"));
        assertFalse(UnrecordedTransferMatcher.matches("", "anything"));
        assertFalse(UnrecordedTransferMatcher.matches(null, "anything"));
    }

    @Test
    void searchesEveryTextFieldAndIgnoresNulls() {
        assertTrue(UnrecordedTransferMatcher.matches("Rahul", null, "from RAHUL"));
        assertTrue(UnrecordedTransferMatcher.matches("Rahul", "Transfer", "UPI rahul"));
        assertFalse(UnrecordedTransferMatcher.matches("Rahul", null, null));
    }
}
