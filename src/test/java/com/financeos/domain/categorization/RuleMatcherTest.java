package com.financeos.domain.categorization;

import com.financeos.core.exception.ValidationException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class RuleMatcherTest {

    private static boolean matches(MatchType type, String pattern, String description) {
        return RuleMatcher.matches(type, pattern, RuleMatcher.MatchContext.of(description));
    }

    @Test
    public void merchantKeyMatchesNormalizedDescription() {
        // Normalizer uppercases, strips punctuation, drops 3+-digit tokens and noise words.
        assertTrue(matches(MatchType.MERCHANT_KEY, "STARBUCKS COFFEE", "STARBUCKS COFFEE #1234 UPI PAYMENT"));
        // Punctuation becomes a token boundary, so the key can span it as a space.
        assertTrue(matches(MatchType.MERCHANT_KEY, "STAR BUCKS", "STAR-BUCKS COFFEE"));
        assertTrue(matches(MatchType.MERCHANT_KEY, "SWIGGY", "upi/swiggy/9834721/order"));
        assertFalse(matches(MatchType.MERCHANT_KEY, "AMAZON", "SWIGGY ORDER"));
        // Keys shorter than 3 chars never match.
        assertFalse(matches(MatchType.MERCHANT_KEY, "SW", "SW BANGALORE"));
    }

    @Test
    public void containsMatchesRawCaseInsensitive() {
        assertTrue(matches(MatchType.CONTAINS, "upi-autopay/042", "UPI-AUTOPAY/042/NETFLIX"));
        assertTrue(matches(MatchType.CONTAINS, "NetFlix", "upi-autopay/042/netflix"));
        // Raw match: punctuation is significant, unlike MERCHANT_KEY.
        assertFalse(matches(MatchType.CONTAINS, "UPI AUTOPAY 042", "UPI-AUTOPAY/042/NETFLIX"));
    }

    @Test
    public void startsWithMatchesTrimmedPrefix() {
        assertTrue(matches(MatchType.STARTS_WITH, "ACH/", "ACH/SALARY CREDIT"));
        assertTrue(matches(MatchType.STARTS_WITH, "ach/", "  ACH/SALARY CREDIT")); // leading whitespace trimmed
        assertFalse(matches(MatchType.STARTS_WITH, "ACH/", "POS ACH/SALARY"));
    }

    @Test
    public void exactMatchesWholeDescription() {
        assertTrue(matches(MatchType.EXACT, "NEFT SALARY", "neft salary"));
        assertTrue(matches(MatchType.EXACT, " NEFT SALARY ", "NEFT SALARY"));
        assertFalse(matches(MatchType.EXACT, "NEFT SALARY", "NEFT SALARY CREDIT"));
    }

    @Test
    public void regexMatchesCaseInsensitivePartial() {
        assertTrue(matches(MatchType.REGEX, "NEFT.*(HDFC|ICICI)", "neft transfer to icici bank"));
        assertFalse(matches(MatchType.REGEX, "NEFT.*(HDFC|ICICI)", "IMPS TRANSFER TO SBI"));
        assertTrue(matches(MatchType.REGEX, "^UPI/\\d{6,}", "UPI/9834721234/SWIGGY"));
    }

    @Test
    public void invalidStoredRegexNeverMatchesAndNeverThrows() {
        assertFalse(matches(MatchType.REGEX, "([unclosed", "anything at all"));
    }

    @Test
    public void catastrophicRegexTimesOutInsteadOfHanging() {
        // (a+)+$ against a long run of 'a' with a non-matching tail backtracks
        // exponentially; the deadline CharSequence must abort it as a non-match.
        String input = "a".repeat(60) + "b";
        long start = System.currentTimeMillis();
        assertFalse(matches(MatchType.REGEX, "(a+)+$", input));
        long elapsed = System.currentTimeMillis() - start;
        assertTrue(elapsed < 5_000, "regex evaluation took " + elapsed + "ms, guard did not fire");
    }

    @Test
    public void blankInputsNeverMatch() {
        assertFalse(matches(MatchType.CONTAINS, "SWIGGY", null));
        assertFalse(matches(MatchType.CONTAINS, "SWIGGY", "   "));
        assertFalse(matches(MatchType.CONTAINS, null, "SWIGGY ORDER"));
        assertFalse(matches(MatchType.CONTAINS, "  ", "SWIGGY ORDER"));
    }

    @Test
    public void nullMatchTypeFallsBackToMerchantKey() {
        assertTrue(matches(null, "SWIGGY", "UPI SWIGGY BANGALORE"));
    }

    @Test
    public void canonicalizeMerchantKeyNormalizes() {
        assertEquals("SWIGGY INSTAMART", RuleMatcher.canonicalizePattern(MatchType.MERCHANT_KEY, "Swiggy*Instamart-9938"));
        assertThrows(ValidationException.class,
                () -> RuleMatcher.canonicalizePattern(MatchType.MERCHANT_KEY, "UPI 123456")); // normalizes to nothing
    }

    @Test
    public void canonicalizeRawTypesKeepTextAsTyped() {
        assertEquals("upi-autopay/042", RuleMatcher.canonicalizePattern(MatchType.CONTAINS, " upi-autopay/042 "));
        assertEquals("ACH/", RuleMatcher.canonicalizePattern(MatchType.STARTS_WITH, "ACH/"));
        // EXACT has no minimum length beyond non-blank.
        assertEquals("AB", RuleMatcher.canonicalizePattern(MatchType.EXACT, "AB"));
    }

    @Test
    public void canonicalizeRejectsInvalidPatterns() {
        assertThrows(ValidationException.class, () -> RuleMatcher.canonicalizePattern(MatchType.CONTAINS, "ab"));
        assertThrows(ValidationException.class, () -> RuleMatcher.canonicalizePattern(MatchType.CONTAINS, "  "));
        assertThrows(ValidationException.class, () -> RuleMatcher.canonicalizePattern(MatchType.REGEX, "([unclosed"));
        assertThrows(ValidationException.class,
                () -> RuleMatcher.canonicalizePattern(MatchType.REGEX, "a".repeat(RuleMatcher.MAX_REGEX_LENGTH + 1)));
        assertThrows(ValidationException.class,
                () -> RuleMatcher.canonicalizePattern(MatchType.CONTAINS, "a".repeat(RuleMatcher.MAX_PATTERN_LENGTH + 1)));
    }
}
