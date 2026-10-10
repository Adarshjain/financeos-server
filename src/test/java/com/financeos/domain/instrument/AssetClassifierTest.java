package com.financeos.domain.instrument;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** AMFI-header, name and tax-class rules of {@link AssetClassifier}. */
class AssetClassifierTest {

    private static final String OE = "Open Ended Schemes(";

    // ------------------------------------------------------------------ header detection

    @Test
    void recognisesCategoryHeaders() {
        assertTrue(AssetClassifier.isCategoryHeader("Open Ended Schemes(Equity Scheme - Large Cap Fund)"));
        assertTrue(AssetClassifier.isCategoryHeader("Close Ended Schemes ( Income )"));
        assertTrue(AssetClassifier.isCategoryHeader("Interval Fund Schemes(Income)"));
        assertTrue(AssetClassifier.isCategoryHeader("  open ended schemes(Other Scheme - Index Funds)  "));
    }

    @Test
    void ignoresAmcLinesSchemeLinesAndBlanks() {
        assertFalse(AssetClassifier.isCategoryHeader("Aditya Birla Sun Life Mutual Fund"));
        assertFalse(AssetClassifier.isCategoryHeader("119551;INF209K01YN0;;Fund (Growth);1;08-Oct-2026"));
        assertFalse(AssetClassifier.isCategoryHeader("Open Ended Schemes"));
        assertFalse(AssetClassifier.isCategoryHeader(""));
        assertFalse(AssetClassifier.isCategoryHeader(null));
    }

    // ------------------------------------------------------------------ AMFI categories

    @ParameterizedTest(name = "{0} -> {2}/{3}")
    @CsvSource(delimiter = '|', value = {
            "Equity Scheme - Large Cap Fund|Some Large Cap Fund|EQUITY|EQUITY_ORIENTED",
            "Equity Scheme - ELSS|Tax Saver|EQUITY|EQUITY_ORIENTED",
            "Equity Scheme - Sectoral/ Thematic|Banking Fund|EQUITY|EQUITY_ORIENTED",
            "Debt Scheme - Liquid Fund|Liquid Fund|DEBT|SPECIFIED_DEBT",
            "Debt Scheme - Gilt Fund|Gilt Fund|DEBT|SPECIFIED_DEBT",
            "Debt Scheme - Corporate Bond Fund|Corporate Bond|DEBT|SPECIFIED_DEBT",
            "Hybrid Scheme - Aggressive Hybrid Fund|Equity & Debt|HYBRID|EQUITY_ORIENTED",
            "Hybrid Scheme - Arbitrage Fund|Arbitrage|HYBRID|EQUITY_ORIENTED",
            "Hybrid Scheme - Equity Savings|Equity Savings|HYBRID|EQUITY_ORIENTED",
            "Hybrid Scheme - Dynamic Asset Allocation or Balanced Advantage|BAF|HYBRID|EQUITY_ORIENTED",
            "Hybrid Scheme - Conservative Hybrid Fund|Regular Savings|HYBRID|OTHER",
            "Hybrid Scheme - Multi Asset Allocation|Multi Asset|HYBRID|OTHER",
            "Hybrid Scheme - Balanced Hybrid Fund|Balanced|HYBRID|OTHER",
            "Solution Oriented Scheme - Retirement Fund|Retirement Fund|HYBRID|OTHER",
            "Other Scheme - FoF Overseas|US Equity FoF|INTERNATIONAL|OTHER",
            "Other Scheme - Gold ETF|Gold ETF|GOLD|OTHER",
            "Other Scheme - FoF Domestic|Gold Savings Fund FoF|GOLD|OTHER",
            "Other Scheme - FoF Domestic|Asset Allocator FoF|OTHER|OTHER",
            "Other Scheme - Index Funds|Nifty 50 Index Fund|EQUITY|EQUITY_ORIENTED",
            "Other Scheme - Index Funds|Nifty SDL Apr 2027 Index Fund|DEBT|SPECIFIED_DEBT",
            "Other Scheme - Index Funds|Motilal Oswal S&P 500 Index Fund|INTERNATIONAL|OTHER",
            "Other Scheme - Index Funds|Unnamed Tracker|EQUITY|EQUITY_ORIENTED",
            "Other Scheme - Other  ETFs|Silver ETF|OTHER|OTHER",
    })
    void classifiesMutualFundsByTheirAmfiHeader(String category, String name, AssetClass assetClass, TaxClass taxClass) {
        String header = OE + category + ")";
        AssetClass actual = AssetClassifier.classify(InstrumentType.mutual_fund, header, name);
        assertEquals(assetClass, actual);
        assertEquals(taxClass, AssetClassifier.taxClass(InstrumentType.mutual_fund, actual, header, name));
    }

    @Test
    void oldCloseEndedIncomeAndGrowthHeaders() {
        assertEquals(AssetClass.DEBT, AssetClassifier.classify(InstrumentType.mutual_fund, "Close Ended Schemes(Income)", "FMP"));
        assertEquals(AssetClass.EQUITY, AssetClassifier.classify(InstrumentType.mutual_fund, "Close Ended Schemes(ELSS)", "LT"));
        assertEquals(AssetClass.OTHER, AssetClassifier.classify(InstrumentType.mutual_fund, "Close Ended Schemes(Growth)", "Fund X"));
    }

    // ------------------------------------------------------------------ names

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = '|', value = {
            "Nippon India ETF Gold BeES|GOLD",
            "SBI Silver ETF|OTHER",
            "Motilal Oswal NASDAQ 100 ETF|INTERNATIONAL",
            "Mirae Asset NYSE FANG+ ETF|INTERNATIONAL",
            "Nippon India ETF Hang Seng BeES|INTERNATIONAL",
            "Mirae Asset S&P 500 Top 50 ETF|INTERNATIONAL",
            "Nippon India ETF Liquid BeES|DEBT",
            "SBI ETF 10 Year Gilt|DEBT",
            "Bharat Bond ETF April 2030|DEBT",
            "Nippon India ETF Nifty 1D Rate Liquid|DEBT",
            "ICICI Prudential Equity & Debt Fund|HYBRID",
            "ICICI Prudential Multi-Asset Fund|HYBRID",
            "Nippon India ETF Nifty 50 BeES|EQUITY",
            "SBI ETF Sensex|EQUITY",
            "Nippon India ETF PSU Bank BeES|EQUITY",
            "Parag Parikh Flexi Cap Fund|EQUITY",
            "Axis Bluechip Fund|EQUITY",
            "S&P BSE Sensex ETF|EQUITY",
    })
    void namesImplyAnAssetClass(String name, AssetClass expected) {
        assertEquals(expected, AssetClassifier.fromName(name));
    }

    @Test
    void anUntellingNameImpliesNothing() {
        assertNull(AssetClassifier.fromName("Kotak Something Fund"));
        assertNull(AssetClassifier.fromName("  "));
        assertNull(AssetClassifier.fromName(null));
    }

    @Test
    void stocksAreAlwaysEquity() {
        assertEquals(AssetClass.EQUITY, AssetClassifier.classify(InstrumentType.stock, null, "Goldiam International"));
        assertEquals(TaxClass.EQUITY_ORIENTED, AssetClassifier.taxClass(InstrumentType.stock, AssetClass.GOLD, null, "X"),
                "listed shares are equity for tax even if pinned to another class");
    }

    @Test
    void etfsGoByNameAndDefaultToEquity() {
        assertEquals(AssetClass.GOLD, AssetClassifier.classify(InstrumentType.etf, null, "GOLDBEES Gold ETF"));
        assertEquals(AssetClass.EQUITY, AssetClassifier.classify(InstrumentType.etf, null, "Kotak Something ETF"));
        assertEquals(AssetClass.EQUITY, AssetClassifier.classify(null, null, null));
    }

    @Test
    void fundsWithoutACategoryGoByNameElseOther() {
        assertEquals(AssetClass.EQUITY, AssetClassifier.classify(InstrumentType.mutual_fund, null, "Axis Midcap Fund"));
        assertEquals(AssetClass.DEBT, AssetClassifier.classify(InstrumentType.mutual_fund, " ", "HDFC Liquid Fund"));
        assertEquals(AssetClass.OTHER, AssetClassifier.classify(InstrumentType.mutual_fund, null, "Kotak Something Fund"));
    }

    // ------------------------------------------------------------------ tax class

    @Test
    void taxClassFollowsTheAssetClass() {
        assertEquals(TaxClass.EQUITY_ORIENTED, AssetClassifier.taxClass(InstrumentType.etf, AssetClass.EQUITY, null, null));
        assertEquals(TaxClass.SPECIFIED_DEBT, AssetClassifier.taxClass(InstrumentType.etf, AssetClass.DEBT, null, null));
        assertEquals(TaxClass.OTHER, AssetClassifier.taxClass(InstrumentType.etf, AssetClass.GOLD, null, null));
        assertEquals(TaxClass.OTHER, AssetClassifier.taxClass(InstrumentType.etf, AssetClass.INTERNATIONAL, null, null));
        assertEquals(TaxClass.OTHER, AssetClassifier.taxClass(InstrumentType.etf, AssetClass.OTHER, null, null));
        assertEquals(TaxClass.OTHER, AssetClassifier.taxClass(InstrumentType.mutual_fund, null, null, null));
    }

    @Test
    void aHybridWithoutACategoryIsJudgedByItsName() {
        assertEquals(TaxClass.EQUITY_ORIENTED,
                AssetClassifier.taxClass(InstrumentType.mutual_fund, AssetClass.HYBRID, null, "ICICI Balanced Advantage Fund"));
        assertEquals(TaxClass.OTHER,
                AssetClassifier.taxClass(InstrumentType.mutual_fund, AssetClass.HYBRID, null, "ICICI Regular Savings"));
        assertEquals(TaxClass.OTHER, AssetClassifier.taxClass(InstrumentType.mutual_fund, AssetClass.HYBRID, null, null));
    }

    // ------------------------------------------------------------------ effective

    private static Instrument instrument(InstrumentType type, String name, AssetClass stored, String category) {
        Instrument i = new Instrument();
        i.setType(type);
        i.setName(name);
        i.setAssetClass(stored);
        i.setSchemeCategory(category);
        return i;
    }

    @Test
    void effectiveUsesTheStoredClassWhenSet() {
        Instrument pinned = instrument(InstrumentType.mutual_fund, "Axis Midcap Fund", AssetClass.GOLD, null);
        assertEquals(AssetClass.GOLD, AssetClassifier.effectiveAssetClass(pinned));
        assertEquals(TaxClass.OTHER, AssetClassifier.effective(pinned).taxClass());
    }

    @Test
    void effectiveAppliesTheRulesWhenNothingIsStored() {
        Instrument fund = instrument(InstrumentType.mutual_fund, "BAF",
                null, OE + "Hybrid Scheme - Dynamic Asset Allocation or Balanced Advantage)");
        AssetClassifier.Classification c = AssetClassifier.effective(fund);
        assertEquals(AssetClass.HYBRID, c.assetClass());
        assertEquals(TaxClass.EQUITY_ORIENTED, c.taxClass());
    }
}
