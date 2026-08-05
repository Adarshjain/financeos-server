package com.financeos.domain.instrument;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Month;

import static org.junit.jupiter.api.Assertions.*;

class FnoSymbolParserTest {

    @Test
    void testParseMonthlyFuture() {
        FnoSymbolParser.FnoParsedContract c = FnoSymbolParser.parse("RELIANCE24AUGFUT");
        assertEquals("RELIANCE", c.underlyingSymbol());
        assertEquals(InstrumentType.future, c.instrumentType());
        assertNull(c.optionType());
        assertNull(c.strikePrice());
        assertNotNull(c.expiryDate());
        assertEquals(2024, c.expiryDate().getYear());
        assertEquals(Month.AUGUST, c.expiryDate().getMonth());
    }

    @Test
    void testParseMonthlyOptionCall() {
        FnoSymbolParser.FnoParsedContract c = FnoSymbolParser.parse("NIFTY24AUG24500CE");
        assertEquals("NIFTY", c.underlyingSymbol());
        assertEquals(InstrumentType.option, c.instrumentType());
        assertEquals(OptionType.CE, c.optionType());
        assertEquals(new BigDecimal("24500"), c.strikePrice());
        assertNotNull(c.expiryDate());
        assertEquals(Month.AUGUST, c.expiryDate().getMonth());
    }

    @Test
    void testParseWeeklyOptionPut() {
        FnoSymbolParser.FnoParsedContract c = FnoSymbolParser.parse("BANKNIFTY2481551000PE");
        assertEquals("BANKNIFTY", c.underlyingSymbol());
        assertEquals(InstrumentType.option, c.instrumentType());
        assertEquals(OptionType.PE, c.optionType());
        assertEquals(new BigDecimal("51000"), c.strikePrice());
        assertNotNull(c.expiryDate());
        assertEquals(LocalDate.of(2024, Month.AUGUST, 15), c.expiryDate());
    }

    @Test
    void testFallbackOption() {
        FnoSymbolParser.FnoParsedContract c = FnoSymbolParser.parse("CUSTOM1000CE");
        assertEquals("CUSTOM", c.underlyingSymbol());
        assertEquals(InstrumentType.option, c.instrumentType());
        assertEquals(OptionType.CE, c.optionType());
        assertEquals(new BigDecimal("1000"), c.strikePrice());
    }
}
