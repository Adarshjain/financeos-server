package com.financeos.domain.investment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.financeos.api.investment.dto.PositionDto;
import com.financeos.api.investment.dto.SummaryResponse;
import com.financeos.core.time.AppTime;
import com.financeos.domain.account.Account;
import com.financeos.domain.holding.Holding;
import com.financeos.domain.holding.HoldingRepository;
import com.financeos.domain.instrument.AssetClass;
import com.financeos.domain.instrument.AssetClassSource;
import com.financeos.domain.instrument.Instrument;
import com.financeos.domain.instrument.InstrumentPrice;
import com.financeos.domain.instrument.InstrumentPriceRepository;
import com.financeos.domain.instrument.InstrumentType;
import com.financeos.domain.instrument.PriceSource;
import com.financeos.domain.instrument.TaxClass;
import com.financeos.domain.instrument.corporateaction.CorporateActionRepository;
import com.financeos.domain.investment.dto.RealizedLot;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * InvestmentService: realised-lot terms by tax class (equity 12m, other 24m, specified debt slab,
 * grandfathering), asset/tax class on positions, and the day change read in one batch query.
 */
class InvestmentTaxTermAndDayChangeTest {

    private static final String LARGE_CAP = "Open Ended Schemes(Equity Scheme - Large Cap Fund)";
    private static final String LIQUID = "Open Ended Schemes(Debt Scheme - Liquid Fund)";

    private HoldingRepository holdingRepository;
    private InvestmentTransactionRepository transactionRepository;
    private InstrumentPriceRepository priceRepository;
    private InvestmentService service;
    private Account broker;

    @BeforeEach
    void setUp() {
        // Day change needs a current price (at most 4 days old): pin today next to the fixture prices.
        AppTime.useClock(java.time.Clock.fixed(LocalDate.of(2026, 10, 9).atStartOfDay(java.time.ZoneId.of("Asia/Kolkata"))
                .toInstant(), java.time.ZoneId.of("Asia/Kolkata")));
        holdingRepository = mock(HoldingRepository.class);
        transactionRepository = mock(InvestmentTransactionRepository.class);
        priceRepository = mock(InstrumentPriceRepository.class);
        service = new InvestmentService(
                transactionRepository,
                holdingRepository,
                mock(com.financeos.domain.account.AccountRepository.class),
                mock(com.financeos.domain.instrument.InstrumentRepository.class),
                priceRepository,
                mock(com.financeos.domain.user.UserRepository.class),
                mock(CorporateActionRepository.class),
                mock(com.financeos.domain.investment.dividend.DividendRepository.class),
                mock(TradeSettlementClassificationRepository.class),
                mock(org.springframework.context.ApplicationEventPublisher.class),
                mock(com.financeos.domain.investment.fno.FnoTradeRepository.class));
        broker = new Account();
        broker.setId(UUID.randomUUID());
        broker.setName("Zerodha");
    }

    @org.junit.jupiter.api.AfterEach
    void tearDown() {
        AppTime.reset();
    }

    private Holding holding(InstrumentType type, String name, String category) {
        Instrument instrument = new Instrument();
        instrument.setId(UUID.randomUUID());
        instrument.setType(type);
        instrument.setName(name);
        instrument.setSchemeCategory(category);
        Holding h = new Holding();
        h.setId(UUID.randomUUID());
        h.setBrokerAccount(broker);
        h.setInstrument(instrument);
        return h;
    }

    private static InvestmentTransaction txn(Holding h, InvestmentTransactionType type, String qty, String price, LocalDate date) {
        InvestmentTransaction t = new InvestmentTransaction();
        t.setHolding(h);
        t.setType(type);
        t.setQuantity(new BigDecimal(qty));
        t.setPrice(new BigDecimal(price));
        t.setTradeDate(date);
        return t;
    }

    private RealizedLot onlyLot(Holding h, LocalDate buy, LocalDate sell) {
        when(holdingRepository.findAllWithDetails()).thenReturn(List.of(h));
        when(transactionRepository.findByHoldingIdOrderByTradeDateAscCreatedAtAsc(h.getId())).thenReturn(List.of(
                txn(h, InvestmentTransactionType.buy, "10", "100", buy),
                txn(h, InvestmentTransactionType.sell, "10", "120", sell)));
        List<RealizedLot> lots = service.getAllRealizedLots();
        assertEquals(1, lots.size());
        return lots.get(0);
    }

    // ------------------------------------------------------------------ realised terms

    @Test
    void equityBoughtBefore2018IsLongAndGrandfathered() {
        RealizedLot lot = onlyLot(holding(InstrumentType.stock, "TATA MOTORS", null),
                LocalDate.of(2017, 6, 1), LocalDate.of(2025, 6, 1));

        assertEquals("long", lot.term());
        assertTrue(lot.grandfathered());
        assertEquals(AssetClass.EQUITY, lot.assetClass());
        assertEquals(TaxClass.EQUITY_ORIENTED, lot.taxClass());
    }

    @Test
    void equityFundHeldUnderAYearIsShortAndNotGrandfathered() {
        RealizedLot lot = onlyLot(holding(InstrumentType.mutual_fund, "Frontline", LARGE_CAP),
                LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 1));

        assertEquals("short", lot.term());
        assertFalse(lot.grandfathered());
        assertEquals(TaxClass.EQUITY_ORIENTED, lot.taxClass());
    }

    @Test
    void debtFundBoughtFromApril2023IsAlwaysSlab() {
        RealizedLot lot = onlyLot(holding(InstrumentType.mutual_fund, "Liquid", LIQUID),
                LocalDate.of(2023, 4, 1), LocalDate.of(2026, 6, 1));

        assertEquals("slab", lot.term());
        assertEquals(AssetClass.DEBT, lot.assetClass());
        assertEquals(TaxClass.SPECIFIED_DEBT, lot.taxClass());
        assertFalse(lot.grandfathered(), "grandfathering is for equity only");
    }

    @Test
    void debtFundBoughtBeforeApril2023FollowsThe24MonthRule() {
        Holding fund = holding(InstrumentType.mutual_fund, "Liquid", LIQUID);
        assertEquals("short", onlyLot(fund, LocalDate.of(2022, 1, 1), LocalDate.of(2024, 1, 1)).term());
        assertEquals("long", onlyLot(fund, LocalDate.of(2022, 1, 1), LocalDate.of(2024, 1, 2)).term());
    }

    @Test
    void goldIsShortUntil24MonthsEvenAfterAYear() {
        RealizedLot lot = onlyLot(holding(InstrumentType.etf, "Nippon India ETF Gold BeES", null),
                LocalDate.of(2024, 1, 1), LocalDate.of(2025, 6, 1));

        assertEquals("short", lot.term());
        assertEquals(AssetClass.GOLD, lot.assetClass());
        assertEquals(TaxClass.OTHER, lot.taxClass());
    }

    @Test
    void aManualOverrideDrivesTheTaxRule() {
        Holding fund = holding(InstrumentType.mutual_fund, "Frontline", LARGE_CAP);
        fund.getInstrument().setAssetClass(AssetClass.INTERNATIONAL);
        fund.getInstrument().setAssetClassSource(AssetClassSource.MANUAL);

        RealizedLot lot = onlyLot(fund, LocalDate.of(2024, 1, 1), LocalDate.of(2025, 6, 1));

        assertEquals("short", lot.term(), "international is long only after 24 months");
        assertEquals(AssetClass.INTERNATIONAL, lot.assetClass());
    }

    // ------------------------------------------------------------------ positions

    private void priced(Holding h, String qty, LocalDate latestAsOf, String latest) {
        when(transactionRepository.findByHoldingIdOrderByTradeDateAscCreatedAtAsc(h.getId())).thenReturn(List.of(
                txn(h, InvestmentTransactionType.buy, qty, "90", LocalDate.of(2026, 1, 5))));
        when(priceRepository.findLatestVisible(org.mockito.ArgumentMatchers.eq(h.getInstrument().getId()), org.mockito.ArgumentMatchers.any())).thenReturn(java.util.List.of(
                new InstrumentPrice(h.getInstrument(), latestAsOf, new BigDecimal(latest), PriceSource.YAHOO)));
    }

    private static Object[] close(Holding h, LocalDate asOf, String close) {
        return new Object[]{h.getInstrument().getId().toString(), Date.valueOf(asOf), new BigDecimal(close)};
    }

    @Test
    void positionsCarryTheDayChangeFromOneBatchQuery() {
        Holding a = holding(InstrumentType.stock, "INFY", null);
        Holding b = holding(InstrumentType.mutual_fund, "Liquid", LIQUID);
        Holding single = holding(InstrumentType.etf, "Gold BeES", null);
        when(holdingRepository.findAllWithDetails()).thenReturn(List.of(a, b, single));
        priced(a, "10", LocalDate.of(2026, 10, 8), "120");
        priced(b, "4", LocalDate.of(2026, 10, 7), "1000");
        priced(single, "1", LocalDate.of(2026, 10, 8), "60");
        List<Object[]> rows = new ArrayList<>();
        rows.add(close(a, LocalDate.of(2026, 10, 7), "100"));     // out of order on purpose
        rows.add(close(a, LocalDate.of(2026, 10, 8), "120"));
        rows.add(close(b, LocalDate.of(2026, 10, 7), "1000"));
        rows.add(close(b, LocalDate.of(2026, 10, 4), "1010"));
        rows.add(close(single, LocalDate.of(2026, 10, 8), "60"));
        when(priceRepository.findLatestTwoCloses(anyCollection(), org.mockito.ArgumentMatchers.any())).thenReturn(rows);

        List<PositionDto> positions = service.getAllPositions();

        PositionDto pa = positions.get(0);
        assertEquals(new BigDecimal("100"), pa.previousClose());
        assertEquals(LocalDate.of(2026, 10, 7), pa.previousCloseAsOf());
        assertEquals(new BigDecimal("200.00"), pa.dayChange());
        assertEquals(new BigDecimal("20.00"), pa.dayChangePct());
        assertEquals(AssetClass.EQUITY, pa.assetClass());
        assertEquals(TaxClass.EQUITY_ORIENTED, pa.taxClass());

        PositionDto pb = positions.get(1);
        assertEquals(LocalDate.of(2026, 10, 4), pb.previousCloseAsOf(), "a NAV a few days back is still the previous one");
        assertEquals(new BigDecimal("-40.00"), pb.dayChange());
        assertEquals(new BigDecimal("-0.99"), pb.dayChangePct());
        assertEquals(AssetClass.DEBT, pb.assetClass());
        assertEquals(TaxClass.SPECIFIED_DEBT, pb.taxClass());

        PositionDto ps = positions.get(2);
        assertNull(ps.previousClose(), "only one stored price");
        assertNull(ps.dayChange());
        assertNull(ps.dayChangePct());

        verify(priceRepository, times(1)).findLatestTwoCloses(anyCollection(), org.mockito.ArgumentMatchers.any());
    }

    @SuppressWarnings("unchecked")
    @Test
    void theBatchAsksForEachInstrumentOnce() {
        Holding a = holding(InstrumentType.stock, "INFY", null);
        Holding sameInstrument = holding(InstrumentType.stock, "INFY", null);
        sameInstrument.setInstrument(a.getInstrument());
        when(holdingRepository.findAllWithDetails()).thenReturn(List.of(a, sameInstrument));
        org.mockito.ArgumentCaptor<Collection<String>> ids = org.mockito.ArgumentCaptor.forClass(Collection.class);
        when(priceRepository.findLatestTwoCloses(ids.capture(), org.mockito.ArgumentMatchers.any())).thenReturn(List.of());

        service.getAllPositions();

        assertEquals(List.of(a.getInstrument().getId().toString()), List.copyOf(ids.getValue()));
    }

    @Test
    void noHoldingsMeansNoPriceQuery() {
        when(holdingRepository.findAllWithDetails()).thenReturn(List.of());
        service.getAllPositions();
        verify(priceRepository, never()).findLatestTwoCloses(anyCollection(), org.mockito.ArgumentMatchers.any());
    }

    // ------------------------------------------------------------------ summary

    @Test
    void summaryTotalsTheDayChangeOverThePreviousValue() {
        Holding a = holding(InstrumentType.stock, "INFY", null);
        Holding b = holding(InstrumentType.stock, "TCS", null);
        Holding noPrev = holding(InstrumentType.stock, "NEW", null);
        when(holdingRepository.findAllWithDetails()).thenReturn(List.of(a, b, noPrev));
        priced(a, "10", LocalDate.of(2026, 10, 8), "120");   // prev 100: +200 on a 1,000 base
        priced(b, "5", LocalDate.of(2026, 10, 7), "180");    // prev 200: −100 on a 1,000 base
        priced(noPrev, "1", LocalDate.of(2026, 10, 9), "50");
        when(priceRepository.findLatestTwoCloses(anyCollection(), org.mockito.ArgumentMatchers.any())).thenReturn(List.of(
                close(a, LocalDate.of(2026, 10, 8), "120"), close(a, LocalDate.of(2026, 10, 7), "100"),
                close(b, LocalDate.of(2026, 10, 7), "180"), close(b, LocalDate.of(2026, 10, 6), "200"),
                close(noPrev, LocalDate.of(2026, 10, 9), "50")));

        SummaryResponse summary = service.getSummary();

        assertEquals(new BigDecimal("100.00"), summary.dayChange());
        assertEquals(new BigDecimal("5.00"), summary.dayChangePct());
        assertEquals(LocalDate.of(2026, 10, 9), summary.priceAsOf());
        assertEquals(LocalDate.of(2026, 10, 7), summary.previousPriceAsOf());
        verify(priceRepository, times(1)).findLatestTwoCloses(anyCollection(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void summaryWithoutAnyPreviousCloseHasNoDayChange() {
        Holding a = holding(InstrumentType.stock, "INFY", null);
        when(holdingRepository.findAllWithDetails()).thenReturn(List.of(a));
        priced(a, "10", LocalDate.of(2026, 10, 8), "120");

        SummaryResponse summary = service.getSummary();

        assertNull(summary.dayChange());
        assertNull(summary.dayChangePct());
        assertEquals(LocalDate.of(2026, 10, 8), summary.priceAsOf());
        assertNull(summary.previousPriceAsOf());
    }
}
