package com.financeos.domain.instrument;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.financeos.domain.instrument.price.AmfiFeedClient;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Stored (global) asset classes: AMFI header for funds, rules for the rest. */
class InstrumentClassificationServiceTest {

    private static final String LARGE_CAP = "Open Ended Schemes(Equity Scheme - Large Cap Fund)";
    private static final String LIQUID = "Open Ended Schemes(Debt Scheme - Liquid Fund)";

    private AmfiFeedClient feed;
    private InstrumentClassificationService service;

    @BeforeEach
    void setUp() {
        feed = mock(AmfiFeedClient.class);
        service = new InstrumentClassificationService(feed);
    }

    private static Instrument instrument(InstrumentType type, String name) {
        Instrument i = new Instrument();
        i.setId(UUID.randomUUID());
        i.setType(type);
        i.setName(name);
        i.setAmfiCode("119551");
        i.setIsin("INF209K01YN0");
        return i;
    }

    @Test
    void aFundTakesItsAmfiHeader() {
        Instrument fund = instrument(InstrumentType.mutual_fund, "ABSL Frontline");
        when(feed.getSchemeCategory("119551", "INF209K01YN0")).thenReturn(LARGE_CAP);

        assertTrue(service.classify(fund));
        assertEquals(AssetClass.EQUITY, fund.getAssetClass());
        assertEquals(LARGE_CAP, fund.getSchemeCategory());
        assertEquals(AssetClassSource.AMFI, fund.getAssetClassSource());
    }

    @Test
    void aFundTheFeedNoLongerListsKeepsItsStoredHeader() {
        Instrument fund = instrument(InstrumentType.mutual_fund, "Some Fund");
        fund.setSchemeCategory(LIQUID);

        assertTrue(service.classify(fund));
        assertEquals(AssetClass.DEBT, fund.getAssetClass());
        assertEquals(LIQUID, fund.getSchemeCategory());
        assertEquals(AssetClassSource.AMFI, fund.getAssetClassSource());
    }

    @Test
    void aNewHeaderReplacesTheStoredOne() {
        Instrument fund = instrument(InstrumentType.mutual_fund, "Recategorised");
        fund.setSchemeCategory(LIQUID);
        fund.setAssetClass(AssetClass.DEBT);
        fund.setAssetClassSource(AssetClassSource.AMFI);
        when(feed.getSchemeCategory(any(), any())).thenReturn(LARGE_CAP);

        assertTrue(service.classify(fund));
        assertEquals(AssetClass.EQUITY, fund.getAssetClass());
        assertEquals(LARGE_CAP, fund.getSchemeCategory());
    }

    @Test
    void aFundWithoutAnyHeaderGoesByNameAsARule() {
        Instrument fund = instrument(InstrumentType.mutual_fund, "HDFC Liquid Fund");

        assertTrue(service.classify(fund));
        assertEquals(AssetClass.DEBT, fund.getAssetClass());
        assertNull(fund.getSchemeCategory());
        assertEquals(AssetClassSource.RULE, fund.getAssetClassSource());
    }

    @Test
    void stocksAreEquityByRuleWithoutTouchingTheFeed() {
        Instrument stock = instrument(InstrumentType.stock, "Reliance");

        assertTrue(service.classify(stock));
        assertEquals(AssetClass.EQUITY, stock.getAssetClass());
        assertEquals(AssetClassSource.RULE, stock.getAssetClassSource());
        verifyNoInteractions(feed);
    }

    @Test
    void etfsGoByNameWithoutTouchingTheFeed() {
        Instrument etf = instrument(InstrumentType.etf, "Nippon India ETF Gold BeES");

        assertTrue(service.classify(etf));
        assertEquals(AssetClass.GOLD, etf.getAssetClass());
        assertEquals(AssetClassSource.RULE, etf.getAssetClassSource());
        verifyNoInteractions(feed);
    }

    @Test
    void anUnchangedClassificationReportsNoChange() {
        Instrument stock = instrument(InstrumentType.stock, "Reliance");
        service.classify(stock);
        assertFalse(service.classify(stock));
    }

    @Test
    void aLeftoverGlobalManualClassIsReclassified() {
        // Users' overrides live in user_instrument_overrides; a MANUAL left on the shared row (V97
        // clears them) is no longer protected and goes back to the AMFI class.
        Instrument fund = instrument(InstrumentType.mutual_fund, "ABSL Frontline");
        fund.setAssetClass(AssetClass.GOLD);
        fund.setAssetClassSource(AssetClassSource.MANUAL);
        when(feed.getSchemeCategory(any(), any())).thenReturn(LARGE_CAP);

        assertTrue(service.classify(fund));
        assertEquals(AssetClass.EQUITY, fund.getAssetClass());
        assertEquals(AssetClassSource.AMFI, fund.getAssetClassSource());
        assertEquals(LARGE_CAP, fund.getSchemeCategory());
    }

    @Test
    void aFeedFailureFallsBackToTheRules() {
        Instrument fund = instrument(InstrumentType.mutual_fund, "Axis Midcap Fund");
        when(feed.getSchemeCategory(any(), any())).thenThrow(new IllegalStateException("feed down"));

        assertTrue(service.classify(fund));
        assertEquals(AssetClass.EQUITY, fund.getAssetClass());
        assertEquals(AssetClassSource.RULE, fund.getAssetClassSource());
    }

    @Test
    void nullIsIgnored() {
        assertFalse(service.classify(null));
    }
}
