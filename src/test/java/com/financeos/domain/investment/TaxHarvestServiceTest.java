package com.financeos.domain.investment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.financeos.api.investment.dto.TaxHarvestResponse;
import com.financeos.api.investment.dto.TaxHarvestResponse.HarvestOpenLot;
import com.financeos.api.investment.dto.TaxHarvestResponse.HarvestRealised;
import com.financeos.core.exception.ValidationException;
import com.financeos.core.time.AppTime;
import com.financeos.domain.account.Account;
import com.financeos.domain.holding.Holding;
import com.financeos.domain.instrument.AssetClass;
import com.financeos.domain.instrument.Instrument;
import com.financeos.domain.instrument.InstrumentType;
import com.financeos.domain.instrument.TaxClass;
import com.financeos.domain.investment.dto.RealizedLot;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Set-off and exemption maths, open-lot terms and the harvest summary, paging and FY bounds. */
class TaxHarvestServiceTest {

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
        assertEquals(0, d(expected).compareTo(actual), expected + " vs " + actual);
    }

    private static RealizedLot sold(TaxClass taxClass, String term, String pnl, LocalDate sellDate) {
        return new RealizedLot(UUID.randomUUID(), UUID.randomUUID(), "Broker", UUID.randomUUID(), "X", InstrumentType.stock,
                sellDate.minusDays(10), sellDate, d("1"), d("100"), d("100").add(d(pnl)), d(pnl), 10, term,
                AssetClass.EQUITY, taxClass, false);
    }

    private static RealizedLot eq(String term, String pnl) {
        return sold(TaxClass.EQUITY_ORIENTED, term, pnl, TODAY);
    }

    // ------------------------------------------------------------------ realised

    @Test
    void rawFiguresSplitGainsAndLossesByTerm() {
        HarvestRealised r = TaxHarvestService.realised(List.of(eq("short", "1000"), eq("short", "-300"), eq("long", "5000"),
                eq("long", "-200")));
        assertMoney("1000", r.stcg());
        assertMoney("300", r.stcl());
        assertMoney("5000", r.ltcg());
        assertMoney("200", r.ltcl());
        assertMoney("700", r.netStcg());
        assertMoney("4800", r.netLtcg());
        assertMoney("125000", r.exemptionLimit());
        assertMoney("4800", r.exemptionUsed());
        assertMoney("120200", r.exemptionLeft());
        assertMoney("0", r.taxableLtcg());
        assertMoney("0", r.stclCarriedForward());
        assertMoney("0", r.ltclCarriedForward());
    }

    @Test
    void shortTermLossesSpillOverOntoLongTermGains() {
        HarvestRealised r = TaxHarvestService.realised(List.of(eq("short", "1000"), eq("short", "-4000"), eq("long", "10000")));
        assertMoney("0", r.netStcg());
        assertMoney("7000", r.netLtcg());
        assertMoney("0", r.stclCarriedForward());
    }

    @Test
    void longTermLossesNeverTouchShortTermGains() {
        HarvestRealised r = TaxHarvestService.realised(List.of(eq("short", "5000"), eq("long", "1000"), eq("long", "-3000")));
        assertMoney("5000", r.netStcg());
        assertMoney("0", r.netLtcg());
        assertMoney("2000", r.ltclCarriedForward());
        assertMoney("125000", r.exemptionLeft());
    }

    @Test
    void unusedShortTermLossCarriesForward() {
        HarvestRealised r = TaxHarvestService.realised(List.of(eq("short", "-9000"), eq("long", "2000")));
        assertMoney("0", r.netLtcg());
        assertMoney("7000", r.stclCarriedForward());
    }

    @Test
    void theExemptionCapsAtOnePointTwoFiveLakh() {
        HarvestRealised r = TaxHarvestService.realised(List.of(eq("long", "200000")));
        assertMoney("125000", r.exemptionUsed());
        assertMoney("0", r.exemptionLeft());
        assertMoney("75000", r.taxableLtcg());
    }

    @Test
    void slabLotsAreShownApartAndOtherClassesLeftOut() {
        HarvestRealised r = TaxHarvestService.realised(List.of(
                sold(TaxClass.SPECIFIED_DEBT, "slab", "800", TODAY),
                sold(TaxClass.SPECIFIED_DEBT, "slab", "-100", TODAY),
                sold(TaxClass.OTHER, "long", "9999", TODAY),
                sold(TaxClass.SPECIFIED_DEBT, "long", "500", TODAY)));
        assertMoney("700", r.slabGains());
        assertMoney("0", r.ltcg());
        assertMoney("0", r.stcg());
    }

    private static RealizedLot unclassified(String term, String pnl) {
        return new RealizedLot(UUID.randomUUID(), UUID.randomUUID(), "Broker", UUID.randomUUID(), "X",
                InstrumentType.etf, TODAY.minusDays(10), TODAY, d("1"), d("100"), d("100").add(d(pnl)), d(pnl), 10,
                term, null, null, false);
    }

    @Test
    void otherClassGainsAreSummedRawByTermAndPooledInTheSetOff() {
        HarvestRealised r = TaxHarvestService.realised(List.of(
                sold(TaxClass.OTHER, "short", "1000", TODAY),
                sold(TaxClass.OTHER, "short", "-300", TODAY),
                sold(TaxClass.OTHER, "long", "5000", TODAY),
                sold(TaxClass.OTHER, "long", "-200", TODAY),
                sold(TaxClass.SPECIFIED_DEBT, "long", "400", TODAY),     // debt bought before 2023-04-01
                sold(TaxClass.SPECIFIED_DEBT, "short", "-50", TODAY),
                unclassified("long", "100"),                              // no tax class reads as OTHER
                sold(TaxClass.SPECIFIED_DEBT, "slab", "800", TODAY),     // slab stays apart
                eq("short", "-2000"),
                eq("long", "3000")));
        TaxHarvestResponse.HarvestOtherGains other = r.otherGains();
        assertMoney("650", other.shortTerm());
        assertMoney("5300", other.longTerm());
        assertMoney("5950", other.total());
        assertMoney("800", r.slabGains());
        // Pooled: short term nets to 700 − 50 + 800 − 2,000 = −550; that loss then offsets the
        // other-class long-term gain (5,300) before the equity one (3,000).
        assertMoney("2000", r.stcl());
        assertMoney("0", r.netStcg());
        assertMoney("7750", r.netLtcg());
        assertMoney("3000", r.netEquityLtcg());
        assertMoney("3000", r.exemptionUsed());
        assertMoney("4750", r.taxableLtcg());
        assertMoney("0", r.stclCarriedForward());
    }

    @Test
    void otherClassLossesMakeTheirTermNegative() {
        TaxHarvestResponse.HarvestOtherGains other = TaxHarvestService.realised(List.of(
                sold(TaxClass.OTHER, "long", "-700", TODAY), sold(TaxClass.OTHER, "short", "200", TODAY))).otherGains();
        assertMoney("200", other.shortTerm());
        assertMoney("-700", other.longTerm());
        assertMoney("-500", other.total());
    }

    @Test
    void noLotsGiveZeroOtherGains() {
        TaxHarvestResponse.HarvestOtherGains other = TaxHarvestService.realised(List.of(eq("long", "100"))).otherGains();
        assertMoney("0", other.shortTerm());
        assertMoney("0", other.longTerm());
        assertMoney("0", other.total());
    }

    // ------------------------------------------------------------------ open lots

    private static Holding holding(InstrumentType type, AssetClass assetClass, String name) {
        Instrument instrument = new Instrument();
        instrument.setId(UUID.randomUUID());
        instrument.setType(type);
        instrument.setName(name);
        instrument.setAssetClass(assetClass);
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

    @Test
    void anEquityLotIsLongTermAfterAYear() {
        Holding h = holding(InstrumentType.stock, AssetClass.EQUITY, "Infy");
        HarvestOpenLot shortLot = TaxHarvestService.openLot(h, d("150"), lot(TODAY.minusDays(350), "10", "100"), TODAY);
        assertEquals("short", shortLot.term());
        assertEquals(TODAY.minusDays(350).plusDays(366), shortLot.longTermOn());
        assertEquals(16, shortLot.daysToLongTerm());
        assertMoney("1000", shortLot.cost());
        assertMoney("1500", shortLot.value());
        assertMoney("500", shortLot.gain());
        assertEquals("Infy", shortLot.instrument());
        assertEquals("Zerodha", shortLot.broker());
        assertEquals(TaxClass.EQUITY_ORIENTED, shortLot.taxClass());
        assertEquals(AssetClass.EQUITY, shortLot.assetClass());
        assertFalse(shortLot.grandfathered());

        HarvestOpenLot longLot = TaxHarvestService.openLot(h, d("150"), lot(TODAY.minusDays(366), "1", "100"), TODAY);
        assertEquals("long", longLot.term());
        assertEquals(0, longLot.daysToLongTerm());

        HarvestOpenLot old = TaxHarvestService.openLot(h, d("150"), lot(LocalDate.of(2017, 5, 1), "1", "10"), TODAY);
        assertTrue(old.grandfathered());
    }

    @Test
    void newDebtIsSlabAndNeverTurnsLong() {
        Holding h = holding(InstrumentType.mutual_fund, AssetClass.DEBT, "Liquid Fund");
        HarvestOpenLot slab = TaxHarvestService.openLot(h, d("10"), lot(LocalDate.of(2023, 4, 1), "5", "8"), TODAY);
        assertEquals("slab", slab.term());
        assertNull(slab.longTermOn());
        assertNull(slab.daysToLongTerm());
        assertEquals(TaxClass.SPECIFIED_DEBT, slab.taxClass());

        HarvestOpenLot oldDebt = TaxHarvestService.openLot(h, d("10"), lot(LocalDate.of(2023, 3, 31), "5", "8"), TODAY);
        assertEquals("long", oldDebt.term());
        assertEquals(LocalDate.of(2025, 4, 1), oldDebt.longTermOn());
    }

    @Test
    void goldTurnsLongAfterTwoYears() {
        Holding h = holding(InstrumentType.etf, AssetClass.GOLD, "Gold ETF");
        LocalDate buy = TODAY.minusMonths(24);
        HarvestOpenLot gold = TaxHarvestService.openLot(h, d("60"), lot(buy, "1", "50"), TODAY);
        assertEquals("short", gold.term());
        assertEquals(buy.plusMonths(24).plusDays(1), gold.longTermOn());
        assertEquals(1, gold.daysToLongTerm());
        assertEquals(TaxClass.OTHER, gold.taxClass());
    }

    @Test
    void aLotWithoutAPriceIsValuedAtCost() {
        Holding h = holding(InstrumentType.stock, AssetClass.EQUITY, "Delisted");
        HarvestOpenLot lot = TaxHarvestService.openLot(h, null, lot(TODAY.minusDays(30), "3", "40"), TODAY);
        assertNull(lot.price());
        assertMoney("120", lot.value());
        assertMoney("0", lot.gain());
    }

    // ------------------------------------------------------------------ summary + paging through harvest()

    private InvestmentService.HoldingLots holdingLots(Holding h, String price, List<HoldingTrace.OpenLot> open,
                                                      List<RealizedLot> realised) {
        HoldingPosition position = new HoldingPosition(h, d("0"), d("0"), d("0"), price == null ? null : d(price), TODAY,
                null, null, null, null, d("0"), d("0"), d("0"), d("0"), null, null);
        return new InvestmentService.HoldingLots(h, position, open, realised);
    }

    @Test
    void harvestSummarisesTheOpenLotsAgainstTheExemptionLeft() {
        Holding stock = holding(InstrumentType.stock, AssetClass.EQUITY, "Stock");
        Holding gold = holding(InstrumentType.etf, AssetClass.GOLD, "Gold");
        List<HoldingTrace.OpenLot> stockLots = List.of(
                lot(TODAY.minusDays(400), "10", "100"),   // long, +1,000 at 200? price 200 → +1,000
                lot(TODAY.minusDays(350), "10", "150"),   // short, turns long in 16 days, +500
                lot(TODAY.minusDays(100), "10", "260"),   // short, far, -600
                lot(TODAY.minusDays(380), "10", "250"));  // long, -500
        List<HoldingTrace.OpenLot> goldLots = List.of(lot(TODAY.minusMonths(30), "1", "100"));  // long OTHER +50
        List<RealizedLot> realised = List.of(
                eq("long", "124500"),                                     // leaves 500 of the exemption
                sold(TaxClass.EQUITY_ORIENTED, "long", "99999", LocalDate.of(2026, 3, 31)),  // previous FY
                sold(TaxClass.EQUITY_ORIENTED, "long", "99999", LocalDate.of(2027, 4, 1)));  // next FY
        when(investments.getAllHoldingLots()).thenReturn(List.of(
                holdingLots(stock, "200", stockLots, realised), holdingLots(gold, "150", goldLots, List.of())));

        TaxHarvestResponse r = service.harvest(null, null, null);

        assertEquals(2026, r.fy());
        assertEquals(LocalDate.of(2026, 4, 1), r.fyStart());
        assertEquals(LocalDate.of(2027, 3, 31), r.fyEnd());
        assertMoney("124500", r.realised().ltcg());
        assertMoney("500", r.realised().exemptionLeft());
        assertMoney("500", r.summary().harvestableLtcg());
        assertMoney("1000", r.summary().unrealisedLongTermEquityGain());
        assertEquals(30, r.summary().turningLongTermSoon().withinDays());
        assertEquals(1, r.summary().turningLongTermSoon().count());
        assertMoney("500", r.summary().turningLongTermSoon().gain());
        assertMoney("-600", r.summary().harvestableLosses().shortTerm());
        assertMoney("-500", r.summary().harvestableLosses().longTerm());
        assertMoney("-1100", r.summary().harvestableLosses().total());

        List<BigDecimal> gains = new ArrayList<>();
        r.openLots().items().forEach(l -> gains.add(l.gain()));
        assertEquals(5, r.openLots().totalElements());
        assertMoney("1000", gains.get(0));
        assertMoney("-600", gains.get(gains.size() - 1));
        for (int i = 1; i < gains.size(); i++) {
            assertTrue(gains.get(i - 1).compareTo(gains.get(i)) >= 0, "largest gain first: " + gains);
        }
    }

    @Test
    void openLotsArePagedAndEmptyQuantitiesSkipped() {
        Holding stock = holding(InstrumentType.stock, AssetClass.EQUITY, "Stock");
        List<HoldingTrace.OpenLot> lots = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            lots.add(lot(TODAY.minusDays(10 + i), "1", String.valueOf(100 + i)));
        }
        lots.add(lot(TODAY.minusDays(5), "0", "1"));
        when(investments.getAllHoldingLots()).thenReturn(List.of(holdingLots(stock, "200", lots, List.of())));

        TaxHarvestResponse.HarvestOpenLotPage first = service.harvest(2026, 0, 2).openLots();
        assertEquals(2, first.items().size());
        assertEquals(5, first.totalElements());
        assertEquals(3, first.totalPages());
        TaxHarvestResponse.HarvestOpenLotPage last = service.harvest(2026, 2, 2).openLots();
        assertEquals(1, last.items().size());
        assertEquals(0, service.harvest(2026, 9, 2).openLots().items().size());
        assertEquals(TaxHarvestService.DEFAULT_PAGE_SIZE, service.harvest(2026, null, null).openLots().size());
        assertEquals(TaxHarvestService.MAX_PAGE_SIZE, service.harvest(2026, 0, 1000).openLots().size());
    }

    @Test
    void theFinancialYearDefaultsToTodaysAndIsValidated() {
        when(investments.getAllHoldingLots()).thenReturn(List.of());
        assertEquals(2026, TaxHarvestService.currentFy(LocalDate.of(2026, 4, 1)));
        assertEquals(2025, TaxHarvestService.currentFy(LocalDate.of(2026, 3, 31)));
        TaxHarvestResponse past = service.harvest(2024, null, null);
        assertEquals(LocalDate.of(2024, 4, 1), past.fyStart());
        assertEquals(LocalDate.of(2025, 3, 31), past.fyEnd());
        assertEquals(0, past.openLots().totalElements());
        assertEquals(1, past.openLots().totalPages());
        assertThrows(ValidationException.class, () -> service.harvest(1999, null, null));
        assertThrows(ValidationException.class, () -> service.harvest(2101, null, null));
    }

    @Test
    void otherGainsOnlyCountLotsSoldInTheFinancialYear() {
        Holding gold = holding(InstrumentType.etf, AssetClass.GOLD, "Gold");
        List<RealizedLot> realised = List.of(
                sold(TaxClass.OTHER, "long", "900", LocalDate.of(2026, 4, 1)),
                sold(TaxClass.OTHER, "short", "40", LocalDate.of(2027, 3, 31)),
                sold(TaxClass.OTHER, "long", "99999", LocalDate.of(2026, 3, 31)),
                sold(TaxClass.OTHER, "long", "99999", LocalDate.of(2027, 4, 1)));
        when(investments.getAllHoldingLots()).thenReturn(List.of(holdingLots(gold, "100", List.of(), realised)));

        TaxHarvestResponse.HarvestOtherGains other = service.harvest(2026, null, null).realised().otherGains();
        assertMoney("40", other.shortTerm());
        assertMoney("900", other.longTerm());
        assertMoney("940", other.total());
    }
}
