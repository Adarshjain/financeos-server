package com.financeos.domain.investment.dividend;

import com.financeos.domain.holding.Holding;
import com.financeos.domain.instrument.Instrument;
import com.financeos.domain.transaction.Transaction;
import com.financeos.domain.transaction.TransactionType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DividendMatcherTest {

    private static final LocalDate BASE = LocalDate.of(2026, 8, 1);

    private static Instrument instrument(String name, String symbol) {
        Instrument i = mock(Instrument.class);
        when(i.getName()).thenReturn(name);
        when(i.getSymbol()).thenReturn(symbol);
        return i;
    }

    private static Dividend dividend(String gross, String tds, DividendType type, Instrument instrument) {
        Holding h = new Holding();
        h.setInstrument(instrument);
        Dividend d = new Dividend();
        d.setHolding(h);
        d.setType(type);
        d.setAmount(new BigDecimal(gross));
        d.setTds(tds == null ? null : new BigDecimal(tds));
        d.setExDate(BASE);
        d.setPayDate(BASE);
        return d;
    }

    private static Transaction credit(String amount, LocalDate date, String description) {
        Transaction t = new Transaction();
        t.setType(TransactionType.CREDIT);
        t.setAmount(new BigDecimal(amount));
        t.setDate(date);
        t.setSourcedDescription(description);
        return t;
    }

    private static final Instrument INFY = instrument("Infosys Limited", "INFY");

    // --- tolerance & band ---------------------------------------------------------------------------

    @Test
    void tolerance_isAtLeastOneRupeeOrHalfAPercent() {
        assertEquals(new BigDecimal("1.00"), DividendMatcher.tolerance(new BigDecimal("100")));
        assertEquals(new BigDecimal("50.00"), DividendMatcher.tolerance(new BigDecimal("10000")));
    }

    @Test
    void band_spansSeventyEightPercentToTenTimesGrossPlusTolerance() {
        assertEquals(new BigDecimal("775.00"), DividendMatcher.bandLow(new BigDecimal("1000")));
        assertEquals(new BigDecimal("10105.00"), DividendMatcher.bandHigh(new BigDecimal("1000"))); // 10.10× + tol: a 10.08× credit stays in reach
        // gross ₹1: 0.78 − ₹1 tolerance would go negative → clamped to zero
        assertEquals(0, DividendMatcher.bandLow(new BigDecimal("1")).compareTo(BigDecimal.ZERO));
    }

    // --- tiers -------------------------------------------------------------------------------------------

    @Test
    void exactGross_isTopTierWithZeroVarianceAndNoImpliedTds() {
        Optional<DividendMatcher.Scored> s = DividendMatcher.score(
                dividend("1000", null, DividendType.dividend, INFY), credit("1000.00", BASE, "NEFT CR"), BASE);
        assertTrue(s.isPresent());
        assertEquals(DividendMatchTier.EXACT, s.get().tier());
        assertEquals(100, s.get().score());
        assertTrue(s.get().reasons().contains(DividendMatchReason.EXACT_GROSS));
        assertNull(s.get().impliedTds());
        assertEquals(0, s.get().variance().compareTo(BigDecimal.ZERO));
    }

    @Test
    void exactGross_toleratesRoundingDifferences() {
        Optional<DividendMatcher.Scored> s = DividendMatcher.score(
                dividend("1000", null, DividendType.dividend, INFY), credit("999.50", BASE, "NEFT CR"), BASE);
        assertEquals(DividendMatchTier.EXACT, s.orElseThrow().tier());
    }

    @Test
    void netOfRecordedTds_isExactTier() {
        Optional<DividendMatcher.Scored> s = DividendMatcher.score(
                dividend("1000", "100", DividendType.dividend, INFY), credit("900", BASE, "NEFT CR"), BASE);
        assertEquals(DividendMatchTier.EXACT, s.orElseThrow().tier());
        assertTrue(s.get().reasons().contains(DividendMatchReason.NET_OF_RECORDED_TDS));
        assertEquals(0, s.get().variance().compareTo(BigDecimal.ZERO));
        assertNull(s.get().impliedTds());
    }

    @Test
    void netOfTenPercent_whenNoTdsRecorded_impliesTds() {
        Optional<DividendMatcher.Scored> s = DividendMatcher.score(
                dividend("1000", null, DividendType.dividend, INFY), credit("900", BASE, "NEFT CR"), BASE);
        assertEquals(DividendMatchTier.NET_OF_TDS, s.orElseThrow().tier());
        assertEquals(85, s.get().score());
        assertTrue(s.get().reasons().contains(DividendMatchReason.NET_OF_10PCT_TDS));
        assertEquals(0, s.get().impliedTds().compareTo(new BigDecimal("100")));
        assertEquals(0, s.get().variance().compareTo(new BigDecimal("-100")));
    }

    @Test
    void netOfTenPercent_notUsedWhenTdsAlreadyRecorded() {
        // tds=50 → expected net 950; received 900 is neither gross nor net → needs narration to agree
        Optional<DividendMatcher.Scored> silent = DividendMatcher.score(
                dividend("1000", "50", DividendType.dividend, INFY), credit("900", BASE, "NEFT CR"), BASE);
        assertTrue(silent.isEmpty());

        Optional<DividendMatcher.Scored> named = DividendMatcher.score(
                dividend("1000", "50", DividendType.dividend, INFY), credit("900", BASE, "ACH C- INFOSYS LTD DIVIDEND"), BASE);
        assertEquals(DividendMatchTier.FUZZY, named.orElseThrow().tier());
        assertEquals(0, named.get().impliedTds().compareTo(new BigDecimal("100")));
        assertEquals(0, named.get().variance().compareTo(new BigDecimal("-50")));
    }

    @Test
    void fuzzyWithinBand_needsKeywordAndNameAndScoresFifty() {
        Optional<DividendMatcher.Scored> s = DividendMatcher.score(
                dividend("1000", null, DividendType.dividend, INFY), credit("800", BASE, "ACH C- INFOSYS LTD DIVIDEND"), BASE);
        assertEquals(DividendMatchTier.FUZZY, s.orElseThrow().tier());
        assertEquals(60, s.get().score()); // 50 + keyword 5 + name 5
        assertTrue(s.get().reasons().containsAll(java.util.List.of(
                DividendMatchReason.AMOUNT_WITHIN_BAND, DividendMatchReason.DIVIDEND_KEYWORD, DividendMatchReason.NAME_MATCH)));
        assertEquals(0, s.get().impliedTds().compareTo(new BigDecimal("200")));
    }

    @Test
    void fuzzy_rejectedWithoutKeyword() {
        assertTrue(DividendMatcher.score(
                dividend("1000", null, DividendType.dividend, INFY), credit("800", BASE, "NEFT INFOSYS LTD"), BASE).isEmpty());
    }

    @Test
    void fuzzy_rejectedWithoutCompanyName() {
        assertTrue(DividendMatcher.score(
                dividend("1000", null, DividendType.dividend, INFY), credit("800", BASE, "ACH C- SOMEONE DIVIDEND"), BASE).isEmpty());
    }

    @Test
    void fuzzy_rejectedBelowBandEvenWhenNarrationAgrees() {
        assertTrue(DividendMatcher.score(
                dividend("1000", null, DividendType.dividend, INFY), credit("700", BASE, "ACH C- INFOSYS LTD DIVIDEND"), BASE).isEmpty());
    }

    @Test
    void splitRatioSuspect_whenReceivedIsAMultipleAndNarrationAgrees() {
        Optional<DividendMatcher.Scored> s = DividendMatcher.score(
                dividend("500", null, DividendType.dividend, INFY), credit("1000", BASE, "INFOSYS LTD DIV"), BASE);
        assertEquals(DividendMatchTier.FUZZY, s.orElseThrow().tier());
        assertTrue(s.get().reasons().contains(DividendMatchReason.SPLIT_RATIO_SUSPECT));
        assertEquals(50, s.get().score()); // 40 + 5 + 5
        assertNull(s.get().impliedTds());
        assertEquals(0, s.get().variance().compareTo(new BigDecimal("500")));
    }

    @Test
    void aboveGrossButNotASplitRatio_isRejected() {
        assertTrue(DividendMatcher.score(
                dividend("1000", null, DividendType.dividend, INFY), credit("1300", BASE, "INFOSYS LTD DIV"), BASE).isEmpty());
    }

    @Test
    void symbolHit_outranksNameMatchAndIsReportedOnce() {
        Optional<DividendMatcher.Scored> s = DividendMatcher.score(
                dividend("1000", null, DividendType.dividend, INFY), credit("1000", BASE, "INFY DIVIDEND"), BASE);
        assertEquals(110, s.orElseThrow().score()); // 100 + keyword + symbol
        assertTrue(s.get().reasons().contains(DividendMatchReason.SYMBOL_MATCH));
        assertFalse(s.get().reasons().contains(DividendMatchReason.NAME_MATCH));
    }

    @Test
    void datePenalty_oneUnitPerFourDaysCappedAtFifteen() {
        Dividend d = dividend("1000", null, DividendType.dividend, INFY);
        assertEquals(90, DividendMatcher.score(d, credit("1000", BASE.plusDays(40), "x"), BASE).orElseThrow().score());
        assertEquals(85, DividendMatcher.score(d, credit("1000", BASE.plusDays(100), "x"), BASE).orElseThrow().score());
        assertEquals(100, DividendMatcher.score(d, credit("1000", BASE.plusDays(3), "x"), BASE).orElseThrow().score());
    }

    @Test
    void score_emptyWhenGrossMissingOrNonPositive() {
        Dividend zero = dividend("0", null, DividendType.dividend, INFY);
        assertTrue(DividendMatcher.score(zero, credit("0", BASE, "x"), BASE).isEmpty());
        Dividend nul = dividend("1", null, DividendType.dividend, INFY);
        nul.setAmount(null);
        assertTrue(DividendMatcher.score(nul, credit("1", BASE, "x"), BASE).isEmpty());
    }

    @Test
    void score_toleratesMissingHoldingAndDates() {
        Dividend d = dividend("1000", null, DividendType.dividend, INFY);
        d.setHolding(null);
        Transaction t = credit("1000", null, null);
        assertEquals(100, DividendMatcher.score(d, t, null).orElseThrow().score());
    }

    // --- keywords & names ------------------------------------------------------------------------------

    @Test
    void hasKeyword_dividendRowsMatchDivTokensAndIdcw() {
        assertTrue(DividendMatcher.hasKeyword("ACH C- HDFC BANK DIV", DividendType.dividend));
        assertTrue(DividendMatcher.hasKeyword("NEFT-INFOSYS LIMITED-DIVIDEND", DividendType.dividend));
        assertTrue(DividendMatcher.hasKeyword("ICICI PRU MF IDCW PAYOUT", DividendType.dividend));
        assertFalse(DividendMatcher.hasKeyword("UPI/INDIVIDUAL PAYMENT", DividendType.dividend));
        assertFalse(DividendMatcher.hasKeyword("UPI/DIVYA S/9876543210/dinner", DividendType.dividend));
        assertFalse(DividendMatcher.hasKeyword("IMPS DIVESH KUMAR", DividendType.dividend));
        assertTrue(DividendMatcher.hasKeyword("NEFT HDFC BANK DIVD", DividendType.dividend));
        assertTrue(DividendMatcher.hasKeyword("ACH C- ITC LTD DVD", DividendType.dividend));
        assertTrue(DividendMatcher.hasKeyword("INFOSYS DIVIDENDPAYOUT", DividendType.dividend));
        assertFalse(DividendMatcher.hasKeyword(null, DividendType.dividend));
        assertFalse(DividendMatcher.hasKeyword("INTEREST CREDIT", DividendType.dividend));
    }

    @Test
    void hasKeyword_interestRowsMatchIntTokensOnly() {
        assertTrue(DividendMatcher.hasKeyword("INT PAID TILL 30-09", DividendType.interest));
        assertTrue(DividendMatcher.hasKeyword("CREDIT INTT 123", DividendType.interest));
        assertTrue(DividendMatcher.hasKeyword("INTEREST CREDIT", DividendType.interest));
        assertFalse(DividendMatcher.hasKeyword("INTL PAYMENT", DividendType.interest));
        assertFalse(DividendMatcher.hasKeyword("HDFC BANK DIV", DividendType.interest));
    }

    @Test
    void nameScore_isShareOfSignificantTokensFound() {
        assertEquals(1.0, DividendMatcher.nameScore(instrument("HDFC Bank Limited", "HDFCBANK"), "NEFT-HDFC BANK LTD-DIV"));
        assertEquals(0.5, DividendMatcher.nameScore(instrument("HDFC Bank Limited", "HDFCBANK"), "HDFC DIV"));
        assertEquals(0.0, DividendMatcher.nameScore(instrument("Tata Consultancy Services Ltd", "TCS"), "TCS DIVIDEND"));
    }

    @Test
    void nameScore_prefixMatchesFourPlusLetterTokensAndIgnoresStopwordsAndNumbers() {
        assertEquals(1.0, DividendMatcher.nameScore(instrument("Infosys Limited", "INFY"), "INFOSYSLTD DIV"));
        assertEquals(0.0, DividendMatcher.nameScore(instrument("Limited Fund Growth", "X"), "whatever"));
        assertEquals(0.0, DividendMatcher.nameScore(null, "x"));
        assertEquals(1.0, DividendMatcher.nameScore(instrument("Nippon India Nifty 50 ETF", "NIFTYBEES"), "NIPPON ETF DIV"));
    }

    @Test
    void symbolMatches_requiresAWholeTokenOfThreePlusLettersThatIsNotNumeric() {
        assertTrue(DividendMatcher.symbolMatches(instrument("Tata Consultancy", "TCS"), "TCS DIVIDEND"));
        assertFalse(DividendMatcher.symbolMatches(instrument("Tata Consultancy", "TCS"), "TCSDIVIDEND"));
        assertFalse(DividendMatcher.symbolMatches(instrument("Some Co", "LT"), "LT DIV"));
        assertFalse(DividendMatcher.symbolMatches(instrument("Some MF", "120503"), "120503 IDCW"));
        assertFalse(DividendMatcher.symbolMatches(instrument("Some MF", null), "x"));
    }

    @Test
    void effectiveDescription_combinesSourcedAndCleanDescriptions() {
        Transaction t = new Transaction();
        assertEquals("", DividendMatcher.effectiveDescription(t));
        t.setDescription("clean");
        assertEquals("clean", DividendMatcher.effectiveDescription(t));
        t.setSourcedDescription("raw");
        assertEquals("raw clean", DividendMatcher.effectiveDescription(t));
        t.setDescription(null);
        assertEquals("raw", DividendMatcher.effectiveDescription(t));
    }

    @Test
    void splitRatio_detectsCommonRatiosWithinOnePercent() {
        assertEquals(2, DividendMatcher.splitRatio(new BigDecimal("1000"), new BigDecimal("500")));
        assertEquals(2, DividendMatcher.splitRatio(new BigDecimal("1010"), new BigDecimal("500")));
        assertNull(DividendMatcher.splitRatio(new BigDecimal("1030"), new BigDecimal("500")));
        assertEquals(10, DividendMatcher.splitRatio(new BigDecimal("5000"), new BigDecimal("500")));
        assertNull(DividendMatcher.splitRatio(new BigDecimal("5000"), BigDecimal.ZERO));
        assertNull(DividendMatcher.splitRatio(null, new BigDecimal("500")));
    }

    // --- boundaries and gaps flagged in review ----------------------------------------------------------

    @Test
    void exact_acceptsReceivedExactlyToleranceAway() {
        // gross 1000 → tol ₹5; 1005 and 995 are EXACT, 1005.01 is not
        Dividend d = dividend("1000", null, DividendType.dividend, INFY);
        assertEquals(DividendMatchTier.EXACT, DividendMatcher.score(d, credit("1005.00", BASE, "x"), BASE).orElseThrow().tier());
        assertEquals(DividendMatchTier.EXACT, DividendMatcher.score(d, credit("995.00", BASE, "x"), BASE).orElseThrow().tier());
        assertTrue(DividendMatcher.score(d, credit("1005.01", BASE, "x"), BASE).isEmpty());
    }

    @Test
    void fuzzy_acceptsReceivedExactlyAtBandLowAndRejectsJustBelow() {
        Dividend d = dividend("1000", null, DividendType.dividend, INFY);
        String narration = "ACH C- INFOSYS LTD DIVIDEND";
        // bandLow = 780 − 5 = 775.00
        assertEquals(DividendMatchTier.FUZZY, DividendMatcher.score(d, credit("775.00", BASE, narration), BASE).orElseThrow().tier());
        assertTrue(DividendMatcher.score(d, credit("774.99", BASE, narration), BASE).isEmpty());
    }

    @Test
    void fuzzy_tickerTokenAloneSatisfiesTheCompanyRequirement() {
        // instrument name shares no token with the narration; the ticker does
        Dividend d = dividend("1000", null, DividendType.dividend, instrument("Tata Consultancy Services", "TCS"));
        Optional<DividendMatcher.Scored> s = DividendMatcher.score(d, credit("800", BASE, "TCS DIVIDEND"), BASE);
        assertEquals(DividendMatchTier.FUZZY, s.orElseThrow().tier());
        assertTrue(s.get().reasons().contains(DividendMatchReason.SYMBOL_MATCH));
    }

    @Test
    void fuzzy_interestRowsUseInterestKeywords() {
        Dividend d = dividend("1000", null, DividendType.interest, instrument("Some Bond Fund", "BOND1"));
        assertEquals(DividendMatchTier.FUZZY, DividendMatcher.score(d, credit("800", BASE, "SOME BOND INTEREST CREDIT"), BASE).orElseThrow().tier());
        assertTrue(DividendMatcher.score(d, credit("800", BASE, "SOME BOND DIVIDEND"), BASE).isEmpty());
    }

    @Test
    void splitRatio_tenTimesWithSlackIsInsideTheQueryBand() {
        BigDecimal gross = new BigDecimal("500");
        assertTrue(DividendMatcher.bandHigh(gross).compareTo(new BigDecimal("5040")) >= 0); // 10.08× must be fetched
        assertEquals(10, DividendMatcher.splitRatio(new BigDecimal("5040"), gross));
    }
}
