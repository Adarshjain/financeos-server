package com.financeos.domain.lending;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class CounterpartyNameMatcherTest {

    private static Counterparty cp(String name) {
        Counterparty cp = new Counterparty();
        cp.setId(UUID.randomUUID());
        cp.setName(name);
        return cp;
    }

    @Test
    void tokenizeLowercasesStripsPunctuationAndDropsShortTokens() {
        // "w/" and "to"/"AJ" fall under the 3-char floor; "Rahul-Sharma" splits on the hyphen.
        assertEquals(List.of("dinner", "rahul", "sharma"),
                CounterpartyNameMatcher.tokenize("  Dinner w/ Rahul-Sharma, to AJ "));
    }

    @Test
    void tokenizeOfPunctuationOnlyIsEmpty() {
        assertEquals(List.of(), CounterpartyNameMatcher.tokenize("--- !!"));
    }

    @Test
    void picksTheOnlyCounterpartySharingAToken() {
        Counterparty rahul = cp("Rahul Sharma");
        Counterparty priya = cp("Priya Nair");

        assertSame(rahul, CounterpartyNameMatcher.bestMatch("UPI/Dinner with rahul", List.of(priya, rahul)));
    }

    @Test
    void higherTokenOverlapWins() {
        Counterparty rahulSharma = cp("Rahul Sharma");
        Counterparty rahulVerma = cp("Rahul Verma");

        assertSame(rahulSharma,
                CounterpartyNameMatcher.bestMatch("Rahul Sharma trip advance", List.of(rahulVerma, rahulSharma)));
    }

    @Test
    void tiesKeepTheFirstCounterpartyInTheList() {
        Counterparty first = cp("Rahul Sharma");
        Counterparty second = cp("Rahul Verma");

        assertSame(first, CounterpartyNameMatcher.bestMatch("Paid rahul back", List.of(first, second)));
        assertSame(second, CounterpartyNameMatcher.bestMatch("Paid rahul back", List.of(second, first)));
    }

    @Test
    void noOverlapIsNull() {
        assertNull(CounterpartyNameMatcher.bestMatch("Swiggy order", List.of(cp("Rahul Sharma"))));
    }

    @Test
    void shortTokensNeverMatch() {
        // "AJ" is under the 3-char floor on both sides, so it can't be the overlap.
        assertNull(CounterpartyNameMatcher.bestMatch("Paid AJ", List.of(cp("AJ"))));
    }

    @Test
    void nullBlankOrTokenlessTextIsNull() {
        List<Counterparty> people = List.of(cp("Rahul Sharma"));

        assertNull(CounterpartyNameMatcher.bestMatch(null, people));
        assertNull(CounterpartyNameMatcher.bestMatch("   ", people));
        assertNull(CounterpartyNameMatcher.bestMatch("to a", people));
    }

    @Test
    void emptyCounterpartyListIsNull() {
        assertNull(CounterpartyNameMatcher.bestMatch("Dinner with Rahul", List.of()));
    }
}
