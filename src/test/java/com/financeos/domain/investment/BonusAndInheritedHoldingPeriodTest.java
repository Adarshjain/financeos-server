package com.financeos.domain.investment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.financeos.core.security.UserContext;
import com.financeos.core.time.AppTime;
import com.financeos.domain.account.Account;
import com.financeos.domain.holding.Holding;
import com.financeos.domain.holding.HoldingRepository;
import com.financeos.domain.instrument.Instrument;
import com.financeos.domain.instrument.InstrumentPrice;
import com.financeos.domain.instrument.InstrumentPriceRepository;
import com.financeos.domain.instrument.InstrumentType;
import com.financeos.domain.instrument.PriceSource;
import com.financeos.domain.instrument.corporateaction.CorporateAction;
import com.financeos.domain.instrument.corporateaction.CorporateActionRepository;
import com.financeos.domain.instrument.corporateaction.CorporateActionType;
import com.financeos.domain.investment.HoldingTrace.LotOrigin;
import com.financeos.domain.investment.HoldingTrace.OpenLot;
import com.financeos.domain.investment.dto.RealizedLot;
import com.financeos.domain.investment.returncalc.XirrCalculator;
import com.financeos.domain.user.User;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Bonus issues are a new zero-cost lot on the ex-date (original lots untouched, so FIFO sells them
 * first), splits still rescale, and demerger/merger shares inherit the parent lots' buy dates
 * (s.2(42A)) while positions, cost and XIRR stay what they were.
 */
class BonusAndInheritedHoldingPeriodTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 9);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private HoldingRepository holdingRepository;
    private InvestmentTransactionRepository transactionRepository;
    private CorporateActionRepository corporateActionRepository;
    private InstrumentPriceRepository priceRepository;
    private InvestmentService service;
    private Account broker;
    private User owner;

    @BeforeEach
    void setUp() {
        AppTime.useClock(Clock.fixed(TODAY.atStartOfDay(IST).toInstant(), IST));
        holdingRepository = mock(HoldingRepository.class);
        transactionRepository = mock(InvestmentTransactionRepository.class);
        corporateActionRepository = mock(CorporateActionRepository.class);
        priceRepository = mock(InstrumentPriceRepository.class);
        service = new InvestmentService(transactionRepository, holdingRepository,
                mock(com.financeos.domain.account.AccountRepository.class),
                mock(com.financeos.domain.instrument.InstrumentRepository.class), priceRepository,
                mock(com.financeos.domain.user.UserRepository.class), corporateActionRepository,
                mock(com.financeos.domain.investment.dividend.DividendRepository.class),
                mock(TradeSettlementClassificationRepository.class),
                mock(org.springframework.context.ApplicationEventPublisher.class),
                mock(com.financeos.domain.investment.fno.FnoTradeRepository.class));
        broker = new Account();
        broker.setId(UUID.randomUUID());
        broker.setName("Zerodha");
        owner = new User();
        owner.setId(UUID.randomUUID());
        UserContext.setCurrentUserId(owner.getId());
    }

    @AfterEach
    void tearDown() {
        AppTime.reset();
        UserContext.clear();
    }

    private static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    private static void assertNum(String expected, BigDecimal actual) {
        assertEquals(0, d(expected).compareTo(actual), expected + " vs " + actual);
    }

    private Holding holding(String name) {
        Instrument instrument = new Instrument();
        instrument.setId(UUID.randomUUID());
        instrument.setType(InstrumentType.stock);
        instrument.setName(name);
        Holding h = new Holding(broker, instrument, null);
        h.setId(UUID.randomUUID());
        h.setUser(owner);
        return h;
    }

    private static InvestmentTransaction trade(Holding h, InvestmentTransactionType type, String qty, String price,
                                               LocalDate date) {
        InvestmentTransaction t = new InvestmentTransaction();
        t.setHolding(h);
        t.setType(type);
        t.setQuantity(d(qty));
        t.setPrice(d(price));
        t.setTradeDate(date);
        return t;
    }

    private static CorporateAction action(Instrument on, CorporateActionType type, int from, int to, LocalDate exDate) {
        CorporateAction ca = new CorporateAction();
        ca.setId(UUID.randomUUID());
        ca.setInstrument(on);
        ca.setType(type);
        ca.setRatioFrom(from);
        ca.setRatioTo(to);
        ca.setExDate(exDate);
        return ca;
    }

    private void stub(Holding h, List<InvestmentTransaction> txns, List<CorporateAction> actions) {
        when(transactionRepository.findByHoldingIdOrderByTradeDateAscCreatedAtAsc(h.getId())).thenReturn(txns);
        when(corporateActionRepository.findByUser_IdAndInstrument_IdOrderByExDateAsc(owner.getId(), h.getInstrument().getId())).thenReturn(actions);
        when(holdingRepository.findById(h.getId())).thenReturn(Optional.of(h));
    }

    private List<RealizedLot> realised(Holding h) {
        List<RealizedLot> lots = new ArrayList<>();
        service.calculateHoldingPosition(h, lots::add);
        return lots;
    }

    // ------------------------------------------------------------------ bonus

    @Test
    void aBonusIsANewZeroCostLotOnTheExDateAndTheOriginalLotIsUntouched() {
        Holding h = holding("Bonus Co");
        LocalDate ex = LocalDate.of(2024, 6, 1);
        stub(h, List.of(trade(h, InvestmentTransactionType.buy, "10", "100", LocalDate.of(2023, 1, 2))),
                List.of(action(h.getInstrument(), CorporateActionType.bonus, 1, 2, ex)));     // 1:1 bonus

        HoldingTrace trace = service.traceHoldingPosition(h.getId());

        List<OpenLot> lots = trace.openLots();
        assertEquals(2, lots.size());
        assertNum("10", lots.get(0).quantity());
        assertNum("100", lots.get(0).costPerUnit());
        assertEquals(LocalDate.of(2023, 1, 2), lots.get(0).buyDate());
        assertEquals(LotOrigin.BUY, lots.get(0).source().origin());
        assertNum("10", lots.get(1).quantity());
        assertNum("0", lots.get(1).costPerUnit());
        assertEquals(ex, lots.get(1).buyDate());
        assertEquals(LotOrigin.BONUS, lots.get(1).source().origin());
        // Totals are what rescaling gave: 20 shares costing 1,000, 50 a share.
        assertNum("20", trace.position().openQty());
        assertNum("1000", trace.position().openCost());
        assertNum("50", trace.position().avgCost());
    }

    @Test
    void fifoSellsTheOriginalSharesBeforeTheBonusShares() {
        Holding h = holding("Bonus Co");
        stub(h, List.of(
                        trade(h, InvestmentTransactionType.buy, "10", "100", LocalDate.of(2023, 1, 2)),
                        trade(h, InvestmentTransactionType.sell, "12", "90", LocalDate.of(2024, 8, 1))),
                List.of(action(h.getInstrument(), CorporateActionType.bonus, 1, 2, LocalDate.of(2024, 6, 1))));

        List<RealizedLot> lots = realised(h);

        assertEquals(2, lots.size());
        RealizedLot original = lots.get(0);
        assertEquals(LocalDate.of(2023, 1, 2), original.buyDate());
        assertNum("10", original.quantity());
        assertNum("-100", original.realizedPnl());          // 10 × (90 − 100)
        assertEquals("long", original.term());
        RealizedLot bonus = lots.get(1);
        assertEquals(LocalDate.of(2024, 6, 1), bonus.buyDate());
        assertNum("2", bonus.quantity());
        assertNum("180", bonus.realizedPnl());              // 2 × (90 − 0)
        assertEquals("short", bonus.term());
        HoldingPosition pos = service.calculateHoldingPosition(h);
        assertNum("8", pos.openQty());
        assertNum("0", pos.openCost());
        assertNum("80", pos.realized());                    // same total as the lots
    }

    @Test
    void aSplitStillRescalesTheExistingLots() {
        Holding h = holding("Split Co");
        stub(h, List.of(trade(h, InvestmentTransactionType.buy, "10", "100", LocalDate.of(2023, 1, 2))),
                List.of(action(h.getInstrument(), CorporateActionType.split, 1, 5, LocalDate.of(2024, 6, 1))));

        HoldingTrace trace = service.traceHoldingPosition(h.getId());

        assertEquals(1, trace.openLots().size());
        assertNum("50", trace.openLots().get(0).quantity());
        assertNum("20", trace.openLots().get(0).costPerUnit());
        assertEquals(LocalDate.of(2023, 1, 2), trace.openLots().get(0).buyDate());
    }

    @Test
    void aBonusOnNoOpenSharesOrWithoutAUsableRatioAddsNothing() {
        Holding h = holding("Bonus Co");
        stub(h, List.of(trade(h, InvestmentTransactionType.buy, "10", "100", LocalDate.of(2023, 1, 2))),
                List.of(action(h.getInstrument(), CorporateActionType.bonus, 2, 2, LocalDate.of(2023, 1, 1)),
                        action(h.getInstrument(), CorporateActionType.bonus, 0, 3, LocalDate.of(2024, 1, 1))));
        HoldingTrace trace = service.traceHoldingPosition(h.getId());
        assertEquals(1, trace.openLots().size());
        assertNum("10", trace.position().openQty());
    }

    @Test
    void theAsOfQuantityCountsTheBonusShares() {
        Holding h = holding("Bonus Co");
        stub(h, List.of(trade(h, InvestmentTransactionType.buy, "10", "100", LocalDate.of(2023, 1, 2))),
                List.of(action(h.getInstrument(), CorporateActionType.bonus, 2, 3, LocalDate.of(2024, 6, 1))));  // 1:2
        assertNum("10", service.openQtyAsOf(h, LocalDate.of(2024, 6, 1)));
        assertNum("15", service.openQtyAsOf(h, LocalDate.of(2024, 6, 2)));
        List<InvestmentService.Lot> lots = service.buildOpenLotsBeforeDate(h, LocalDate.of(2024, 7, 1), true, null);
        assertEquals(2, lots.size());
        assertNum("0", lots.get(1).costPerUnit);
        assertEquals(LocalDate.of(2024, 6, 1), lots.get(1).buyDate);
    }

    // ------------------------------------------------------------------ merger / demerger holding period

    private Holding parentWithTwoLots(Instrument target, CorporateAction ca) {
        Holding parent = holding("Parent Ltd");
        ca.setInstrument(parent.getInstrument());
        ca.setTargetInstrument(target);
        stub(parent, List.of(
                        trade(parent, InvestmentTransactionType.buy, "60", "100", LocalDate.of(2017, 6, 1)),
                        trade(parent, InvestmentTransactionType.buy, "40", "150", LocalDate.of(2023, 3, 1))),
                List.of(ca));
        when(holdingRepository.findByBrokerAccountIdAndInstrumentId(broker.getId(), parent.getInstrument().getId()))
                .thenReturn(Optional.of(parent));
        return parent;
    }

    @Test
    void mergerSharesInheritEachParentLotsBuyDateAndCost() {
        Holding acquirer = holding("Acquirer Ltd");
        LocalDate ex = LocalDate.of(2024, 6, 1);
        CorporateAction merger = action(null, CorporateActionType.merger, 1, 2, ex);
        merger.setCostAllocationPct(d("100"));
        parentWithTwoLots(acquirer.getInstrument(), merger);
        stub(acquirer, List.of(trade(acquirer, InvestmentTransactionType.sell, "150", "100", LocalDate.of(2024, 8, 1))),
                List.of());
        when(corporateActionRepository.findByUser_IdAndTargetInstrument_IdOrderByExDateAsc(owner.getId(), acquirer.getInstrument().getId()))
                .thenReturn(List.of(merger));

        List<InvestmentService.SeedLot> seeds = service.seedLotsFor(acquirer);
        assertEquals(2, seeds.size());
        assertEquals(LocalDate.of(2017, 6, 1), seeds.get(0).buyDate());
        assertEquals(ex, seeds.get(0).date(), "the shares still arrive on the ex-date");
        assertNum("120", seeds.get(0).qty());
        assertNum("50", seeds.get(0).costPerUnit());
        assertEquals(LocalDate.of(2023, 3, 1), seeds.get(1).buyDate());
        assertNum("80", seeds.get(1).qty());
        assertNum("75", seeds.get(1).costPerUnit());

        List<RealizedLot> lots = realised(acquirer);
        assertEquals(2, lots.size());
        assertEquals(LocalDate.of(2017, 6, 1), lots.get(0).buyDate());
        assertEquals("long", lots.get(0).term());
        assertTrue(lots.get(0).grandfathered(), "grandfathering follows the parent's buy date");
        assertNum("6000", lots.get(0).realizedPnl());       // 120 × (100 − 50)
        assertEquals(LocalDate.of(2023, 3, 1), lots.get(1).buyDate());
        assertEquals("long", lots.get(1).term(), "held from 2023-03-01, not from the 2024 ex-date");
        assertFalse(lots.get(1).grandfathered());
        assertNum("750", lots.get(1).realizedPnl());        // 30 × (100 − 75)

        HoldingPosition pos = service.calculateHoldingPosition(acquirer);
        assertNum("50", pos.openQty());
        assertNum("3750", pos.openCost());
        assertNum("6750", pos.realized());
    }

    @Test
    void demergerChildSharesInheritTheParentDatesAndTheirCarvedCost() {
        Holding child = holding("Child Ltd");
        LocalDate ex = LocalDate.of(2024, 6, 1);
        CorporateAction demerger = action(null, CorporateActionType.demerger, 2, 1, ex);
        demerger.setCostAllocationPct(d("20"));
        parentWithTwoLots(child.getInstrument(), demerger);
        stub(child, List.of(), List.of());
        when(corporateActionRepository.findByUser_IdAndTargetInstrument_IdOrderByExDateAsc(owner.getId(), child.getInstrument().getId()))
                .thenReturn(List.of(demerger));

        HoldingTrace trace = service.traceHoldingPosition(child.getId());

        assertEquals(2, trace.openLots().size());
        OpenLot first = trace.openLots().get(0);
        assertEquals(LocalDate.of(2017, 6, 1), first.buyDate());
        assertNum("30", first.quantity());
        assertNum("1200", first.cost());                     // 20% of 60 × 100
        OpenLot second = trace.openLots().get(1);
        assertEquals(LocalDate.of(2023, 3, 1), second.buyDate());
        assertNum("20", second.quantity());
        assertNum("1200", second.cost());                    // 20% of 40 × 150
        assertEquals(ex, trace.events().get(0).date());
        assertNum("50", trace.position().openQty());
        assertNum("2400", trace.position().openCost());
    }

    @Test
    void theMergerXirrBridgeIsUnchangedByTheInheritedDates() {
        Holding acquirer = holding("Acquirer Ltd");
        LocalDate ex = LocalDate.of(2024, 6, 1);
        CorporateAction merger = action(null, CorporateActionType.merger, 1, 2, ex);
        merger.setCostAllocationPct(d("100"));
        parentWithTwoLots(acquirer.getInstrument(), merger);
        stub(acquirer, List.of(), List.of());
        when(corporateActionRepository.findByUser_IdAndTargetInstrument_IdOrderByExDateAsc(owner.getId(), acquirer.getInstrument().getId()))
                .thenReturn(List.of(merger));
        when(priceRepository.findLatestVisible(org.mockito.ArgumentMatchers.eq(acquirer.getInstrument().getId()), org.mockito.ArgumentMatchers.any()))
                .thenReturn(java.util.List.of(new InstrumentPrice(acquirer.getInstrument(), TODAY, d("80"), PriceSource.YAHOO)));

        HoldingPosition pos = service.calculateHoldingPosition(acquirer);

        // The acquirer's own flows are the in-kind bridge (−carried cost on the ex-date) and today's
        // value — nothing at the inherited 2017/2023 dates.
        BigDecimal carried = d("12000");                      // 60 × 100 + 40 × 150
        Double expected = XirrCalculator.calculateXirr(List.of(
                new XirrCalculator.Cashflow(ex, carried.negate()),
                new XirrCalculator.Cashflow(TODAY, d("200").multiply(d("80")))));
        assertNotNull(pos.xirr());
        assertEquals(BigDecimal.valueOf(expected).multiply(d("100")).setScale(2, RoundingMode.HALF_UP).doubleValue(),
                pos.xirr());
        assertNum("12000", pos.openCost());
    }
}
