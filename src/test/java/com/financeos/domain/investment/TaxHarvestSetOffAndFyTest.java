package com.financeos.domain.investment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.financeos.api.investment.dto.TaxHarvestResponse;
import com.financeos.api.investment.dto.TaxHarvestResponse.HarvestOpenLot;
import com.financeos.api.investment.dto.TaxHarvestResponse.HarvestRealised;
import com.financeos.core.time.AppTime;
import com.financeos.domain.account.Account;
import com.financeos.domain.holding.Holding;
import com.financeos.domain.instrument.AssetClass;
import com.financeos.domain.instrument.AssetClassifier;
import com.financeos.domain.instrument.Instrument;
import com.financeos.domain.instrument.InstrumentType;
import com.financeos.domain.instrument.TaxClass;
import com.financeos.domain.investment.dto.RealizedLot;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Pooled set-off across asset classes before the equity-only exemption, the exemption by financial
 * year, the past-year response (no open-lot summary), and FIFO-aware harvestable LTCG.
 */
class TaxHarvestSetOffAndFyTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 9);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private InvestmentService investments;
    private TaxHarvestService service;

    @BeforeEach
    void setUp() {
        AppTime.useClock(Clock.fixed(TODAY.atStartOfDay(IST).toInstant(), IST));
        investments = mock(InvestmentService.class);
        service = new TaxHarvestService(investments);
    }

    @AfterEach
    void tearDown() {
        AppTime.reset();
    }

    private static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    private static void assertMoney(String expected, BigDecimal actual) {
        assertMoney(expected, actual, "");
    }

    private static void assertMoney(String expected, BigDecimal actual, String message) {
        assertEquals(0, d(expected).compareTo(actual), message + " " + expected + " vs " + actual);
    }

    private static RealizedLot sold(TaxClass taxClass, String term, String pnl, LocalDate sellDate) {
        return new RealizedLot(UUID.randomUUID(), UUID.randomUUID(), "Broker", UUID.randomUUID(), "X", InstrumentType.etf,
                sellDate.minusDays(10), sellDate, d("1"), d("100"), d("100").add(d(pnl)), d(pnl), 10, term,
                AssetClass.EQUITY, taxClass, false);
    }

    private static RealizedLot eq(String term, String pnl) {
        return sold(TaxClass.EQUITY_ORIENTED, term, pnl, TODAY);
    }

    private static RealizedLot other(String term, String pnl) {
        return sold(TaxClass.OTHER, term, pnl, TODAY);
    }

    // ------------------------------------------------------------------ pooled set-off

    @Test
    void aGoldShortTermLossOffsetsEquityLtcgBeforeTheExemption() {
        HarvestRealised r = TaxHarvestService.realised(List.of(eq("long", "200000"), other("short", "-100000")));

        assertMoney("200000", r.ltcg(), "raw equity LTCG stays raw");
        assertMoney("0", r.stcl(), "stcl is the equity figure; the gold loss is in otherGains");
        assertMoney("-100000", r.otherGains().shortTerm());
        assertMoney("0", r.netStcg());
        assertMoney("100000", r.netLtcg());
        assertMoney("100000", r.netEquityLtcg());
        assertMoney("100000", r.exemptionUsed());
        assertMoney("25000", r.exemptionLeft());
        assertMoney("0", r.taxableLtcg());
        assertMoney("0", r.stclCarriedForward());
    }

    @Test
    void slabLossesCountAsShortTermAndSlabGainsAsShortTermGains() {
        HarvestRealised loss = TaxHarvestService.realised(List.of(
                eq("short", "5000"), sold(TaxClass.SPECIFIED_DEBT, "slab", "-8000", TODAY), eq("long", "10000")));
        assertMoney("-8000", loss.slabGains());
        assertMoney("0", loss.netStcg());
        assertMoney("7000", loss.netLtcg(), "3,000 of short-term loss left offsets LTCG");

        HarvestRealised gain = TaxHarvestService.realised(List.of(
                eq("short", "-5000"), sold(TaxClass.SPECIFIED_DEBT, "slab", "8000", TODAY)));
        assertMoney("3000", gain.netStcg());
        assertMoney("0", gain.stclCarriedForward());
    }

    @Test
    void longTermLossesOffsetOtherClassLtcgBeforeEquityLtcg() {
        HarvestRealised r = TaxHarvestService.realised(List.of(
                eq("long", "150000"), eq("long", "-20000"), other("long", "30000"), other("long", "-50000")));
        // Equity nets to 130,000; other nets to −20,000 → a long-term loss that can only hit LTCG.
        assertMoney("110000", r.netLtcg());
        assertMoney("110000", r.netEquityLtcg());
        assertMoney("110000", r.exemptionUsed());
        assertMoney("15000", r.exemptionLeft());
        assertMoney("0", r.ltclCarriedForward());

        HarvestRealised mixed = TaxHarvestService.realised(List.of(
                eq("long", "100000"), other("long", "40000"), other("short", "-60000")));
        // The short-term loss eats the other-class LTCG first (no exemption there), then 20,000 of equity.
        assertMoney("80000", mixed.netLtcg());
        assertMoney("80000", mixed.netEquityLtcg());
        assertMoney("0", mixed.taxableLtcg());
    }

    @Test
    void longTermLossesNeverReachShortTermGainsOfAnyClass() {
        HarvestRealised r = TaxHarvestService.realised(List.of(other("short", "4000"), eq("long", "-9000")));
        assertMoney("4000", r.netStcg());
        assertMoney("0", r.netLtcg());
        assertMoney("9000", r.ltclCarriedForward());
        assertMoney("0", r.stclCarriedForward());
    }

    @Test
    void otherClassLtcgGetsNoExemption() {
        HarvestRealised r = TaxHarvestService.realised(List.of(other("long", "50000")));
        assertMoney("50000", r.netLtcg());
        assertMoney("0", r.netEquityLtcg());
        assertMoney("0", r.exemptionUsed());
        assertMoney("125000", r.exemptionLeft());
        assertMoney("50000", r.taxableLtcg());
    }

    @Test
    void unabsorbedShortTermLossCarriesForward() {
        HarvestRealised r = TaxHarvestService.realised(List.of(other("short", "-30000"), other("long", "10000"),
                eq("long", "5000")));
        assertMoney("0", r.netLtcg());
        assertMoney("15000", r.stclCarriedForward());
    }

    // ------------------------------------------------------------------ financial year

    @Test
    void theExemptionIsOneLakhBeforeFy2024() {
        assertMoney("100000", TaxHarvestService.exemptionLimit(2023));
        assertMoney("125000", TaxHarvestService.exemptionLimit(2024));
        assertMoney("125000", TaxHarvestService.exemptionLimit(2026));

        RealizedLot lastYear = sold(TaxClass.EQUITY_ORIENTED, "long", "150000", LocalDate.of(2024, 2, 1));
        when(investments.getAllHoldingLots()).thenReturn(List.of(holdingLots(stock(), List.of(), List.of(lastYear))));
        TaxHarvestResponse fy2023 = service.harvest(2023, null, null);
        assertMoney("100000", fy2023.realised().exemptionLimit());
        assertMoney("100000", fy2023.realised().exemptionUsed());
        assertMoney("50000", fy2023.realised().taxableLtcg());
    }

    @Test
    void aPastYearHasNoOpenLotSummaryAndNoOpenLots() {
        Holding h = stock();
        HoldingTrace.OpenLot open = lot(TODAY.minusYears(2), "10", "100");
        RealizedLot booked = sold(TaxClass.EQUITY_ORIENTED, "long", "1000", LocalDate.of(2025, 5, 1));
        when(investments.getAllHoldingLots()).thenReturn(List.of(holdingLots(h, List.of(open), List.of(booked))));

        TaxHarvestResponse past = service.harvest(2025, null, null);
        assertNull(past.summary());
        assertEquals(0, past.openLots().totalElements());
        assertEquals(0, past.openLots().items().size());
        assertMoney("1000", past.realised().ltcg());

        TaxHarvestResponse current = service.harvest(null, null, null);
        assertNotNull(current.summary());
        assertEquals(1, current.openLots().totalElements());
    }

    // ------------------------------------------------------------------ FIFO harvestable

    private static Holding stock() {
        Instrument instrument = new Instrument();
        instrument.setId(UUID.randomUUID());
        instrument.setType(InstrumentType.stock);
        instrument.setName("Stock " + instrument.getId());
        Account broker = new Account();
        broker.setName("Zerodha");
        Holding h = new Holding();
        h.setId(UUID.randomUUID());
        h.setInstrument(instrument);
        h.setBrokerAccount(broker);
        return h;
    }

    private static HoldingTrace.OpenLot lot(LocalDate buy, String qty, String cost) {
        return new HoldingTrace.OpenLot(buy, HoldingTrace.LotSource.BUY, d(qty), d(cost));
    }

    private static InvestmentService.HoldingLots holdingLots(Holding h, List<HoldingTrace.OpenLot> open,
                                                             List<RealizedLot> realised) {
        HoldingPosition position = new HoldingPosition(h, d("0"), d("0"), d("0"), d("200"), TODAY,
                null, null, null, null, d("0"), d("0"), d("0"), d("0"), null, null);
        return new InvestmentService.HoldingLots(h, position, open, realised);
    }

    private static List<HarvestOpenLot> open(Holding h, HoldingTrace.OpenLot... lots) {
        return java.util.Arrays.stream(lots)
                .map(l -> TaxHarvestService.openLot(h, d("200"), l, TODAY))
                .toList();
    }

    @Test
    void harvestableGainIsTheBestFifoPrefixOfLongTermEquityLots() {
        Holding h = stock();
        LocalDate old = TODAY.minusYears(2);
        // A −10,000 lot is sold before the +50,000 one: booking both nets 40,000.
        assertMoney("40000", TaxHarvestService.harvestableLongTermEquityGain(open(h,
                lot(old, "100", "300"), lot(old.plusDays(1), "500", "100"))));
        // A gain then a bigger loss: stop after the gain.
        assertMoney("30000", TaxHarvestService.harvestableLongTermEquityGain(open(h,
                lot(old, "300", "100"), lot(old.plusDays(1), "500", "300"))));
        // A short-term lot first blocks everything behind it.
        assertMoney("0", TaxHarvestService.harvestableLongTermEquityGain(open(h,
                lot(TODAY.minusDays(30), "1", "100"), lot(old, "500", "100"))));
        // Only losses: nothing to harvest.
        assertMoney("0", TaxHarvestService.harvestableLongTermEquityGain(open(h, lot(old, "10", "300"))));
    }

    @Test
    void harvestableLtcgSumsHoldingsAndCapsAtTheExemptionLeft() {
        Holding a = stock();
        Holding b = stock();
        LocalDate old = TODAY.minusYears(2);
        when(investments.getAllHoldingLots()).thenReturn(List.of(
                holdingLots(a, List.of(lot(old, "100", "300"), lot(old.plusDays(1), "500", "100")), List.of()),
                holdingLots(b, List.of(lot(old, "1000", "100")), List.of(eq("long", "60000")))));

        TaxHarvestResponse r = service.harvest(null, null, null);

        // a: 40,000; b: 100,000 → 140,000 uncapped; 65,000 of the exemption is left.
        assertMoney("140000", r.summary().unrealisedLongTermEquityGain());
        assertMoney("65000", r.realised().exemptionLeft());
        assertMoney("65000", r.summary().harvestableLtcg());
    }

    @Test
    void openLotsUseTheHoldingsClassificationNotTheGlobalOne() {
        Holding h = stock();
        h.getInstrument().setType(InstrumentType.etf);       // a listed share stays equity whatever its class
        h.getInstrument().setName("NIFTY BEES");
        AssetClassifier.Classification gold = AssetClassifier.effective(h.getInstrument(), AssetClass.GOLD);
        HarvestOpenLot lot = TaxHarvestService.openLot(h, gold, d("200"), lot(TODAY.minusMonths(18), "1", "100"), TODAY);
        assertEquals(AssetClass.GOLD, lot.assetClass());
        assertEquals(TaxClass.OTHER, lot.taxClass());
        assertEquals("short", lot.term(), "18 months is short for OTHER");

        when(investments.getAllHoldingLots()).thenReturn(List.of(new InvestmentService.HoldingLots(h,
                new HoldingPosition(h, d("0"), d("0"), d("0"), d("200"), TODAY, null, null, null, null, d("0"), d("0"),
                        d("0"), d("0"), null, null),
                List.of(lot(TODAY.minusMonths(18), "1", "100")), List.of(), gold)));
        HarvestOpenLot fromHarvest = service.harvest(null, null, null).openLots().items().get(0);
        assertEquals(AssetClass.GOLD, fromHarvest.assetClass());
    }
}
