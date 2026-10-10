package com.financeos.domain.investment;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.financeos.core.exception.ResourceNotFoundException;
import com.financeos.core.security.UserContext;
import com.financeos.domain.account.Account;
import com.financeos.domain.holding.Holding;
import com.financeos.domain.holding.HoldingRepository;
import com.financeos.domain.instrument.Instrument;
import com.financeos.domain.instrument.InstrumentPrice;
import com.financeos.domain.instrument.InstrumentPriceRepository;
import com.financeos.domain.instrument.InstrumentType;
import com.financeos.domain.instrument.corporateaction.CorporateAction;
import com.financeos.domain.instrument.corporateaction.CorporateActionRepository;
import com.financeos.domain.instrument.corporateaction.CorporateActionType;
import com.financeos.domain.investment.HoldingTrace.Event;
import com.financeos.domain.investment.HoldingTrace.EventKind;
import com.financeos.domain.investment.HoldingTrace.LotOrigin;
import com.financeos.domain.investment.HoldingTrace.OpenLot;
import com.financeos.domain.user.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The engine trace behind {@link InvestmentService#traceHoldingPosition}: ownership, the position
 * it returns (identical to {@code calculateHoldingPosition}), the events in engine order with their
 * quantity effects, and the open lots with their origins summing to the position.
 */
class InvestmentServiceTraceTest {

    private HoldingRepository holdingRepository;
    private InvestmentTransactionRepository transactionRepository;
    private CorporateActionRepository corporateActionRepository;
    private InstrumentPriceRepository priceRepository;
    private TradeSettlementClassificationRepository classificationRepository;
    private InvestmentService investmentService;

    private User owner;
    private Account brokerAccount;
    private Instrument instrument;
    private Holding holding;

    @BeforeEach
    void setUp() {
        holdingRepository = mock(HoldingRepository.class);
        transactionRepository = mock(InvestmentTransactionRepository.class);
        corporateActionRepository = mock(CorporateActionRepository.class);
        priceRepository = mock(InstrumentPriceRepository.class);
        classificationRepository = mock(TradeSettlementClassificationRepository.class);

        investmentService = new InvestmentService(
                transactionRepository,
                holdingRepository,
                mock(com.financeos.domain.account.AccountRepository.class),
                mock(com.financeos.domain.instrument.InstrumentRepository.class),
                priceRepository,
                mock(com.financeos.domain.user.UserRepository.class),
                corporateActionRepository,
                mock(com.financeos.domain.investment.dividend.DividendRepository.class),
                classificationRepository,
                mock(org.springframework.context.ApplicationEventPublisher.class),
                mock(com.financeos.domain.investment.fno.FnoTradeRepository.class)
        );

        owner = new User();
        owner.setId(UUID.randomUUID());
        UserContext.setCurrentUserId(owner.getId());

        brokerAccount = new Account();
        brokerAccount.setId(UUID.randomUUID());
        brokerAccount.setName("Zerodha");

        instrument = instrument("Tata Motors");
        holding = holding(instrument);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    // ---- ownership ----

    @Test
    void missingHoldingIsNotFound() {
        UUID id = UUID.randomUUID();
        when(holdingRepository.findById(id)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> investmentService.traceHoldingPosition(id));
    }

    @Test
    void anotherUsersHoldingIsNotFound() {
        User stranger = new User();
        stranger.setId(UUID.randomUUID());
        holding.setUser(stranger);
        when(holdingRepository.findById(holding.getId())).thenReturn(Optional.of(holding));

        assertThrows(ResourceNotFoundException.class, () -> investmentService.traceHoldingPosition(holding.getId()));
        verifyNoInteractions(transactionRepository);
    }

    @Test
    void holdingWithoutOwnerIsNotFound() {
        holding.setUser(null);
        when(holdingRepository.findById(holding.getId())).thenReturn(Optional.of(holding));

        assertThrows(ResourceNotFoundException.class, () -> investmentService.traceHoldingPosition(holding.getId()));
    }

    // ---- trades ----

    @Test
    void fifoTradesTraceEventsAndOpenLotsAndLeaveThePositionUnchanged() {
        stubHolding(List.of(
                trade(InvestmentTransactionType.buy, "10", "100", LocalDate.of(2024, 1, 1)),
                trade(InvestmentTransactionType.buy, "5", "120", LocalDate.of(2024, 2, 1)),
                trade(InvestmentTransactionType.sell, "12", "150", LocalDate.of(2024, 3, 1))),
                List.of());
        InstrumentPrice price = new InstrumentPrice();
        price.setClose(new BigDecimal("130"));
        price.setAsOf(LocalDate.of(2024, 3, 5));
        when(priceRepository.findLatestVisible(org.mockito.ArgumentMatchers.eq(instrument.getId()), org.mockito.ArgumentMatchers.any())).thenReturn(java.util.List.of(price));

        HoldingTrace trace = investmentService.traceHoldingPosition(holding.getId());

        assertEquals(investmentService.calculateHoldingPosition(holding), trace.position());
        assertEquals(List.of(
                new Event(LocalDate.of(2024, 1, 1), EventKind.BUY, null, new BigDecimal("10"), new BigDecimal("100"), new BigDecimal("10"), new BigDecimal("10")),
                new Event(LocalDate.of(2024, 2, 1), EventKind.BUY, null, new BigDecimal("5"), new BigDecimal("120"), new BigDecimal("5"), new BigDecimal("15")),
                new Event(LocalDate.of(2024, 3, 1), EventKind.SELL, null, new BigDecimal("12"), new BigDecimal("150"), new BigDecimal("-12"), new BigDecimal("3"))),
                trace.events());
        assertEquals(1, trace.openLots().size());
        OpenLot lot = trace.openLots().get(0);
        assertEquals(LocalDate.of(2024, 2, 1), lot.buyDate());
        assertEquals(LotOrigin.BUY, lot.source().origin());
        assertNull(lot.source().action());
        assertEquals(new BigDecimal("3"), lot.quantity());
        assertEquals(new BigDecimal("120"), lot.costPerUnit());
        assertLotsSumToPosition(trace);
    }

    @Test
    void sellBeyondTheOpenLotsTracesOnlyTheQuantityItRemoved() {
        stubHolding(List.of(
                trade(InvestmentTransactionType.buy, "5", "100", LocalDate.of(2024, 1, 1)),
                trade(InvestmentTransactionType.sell, "8", "110", LocalDate.of(2024, 2, 1))),
                List.of());

        HoldingTrace trace = investmentService.traceHoldingPosition(holding.getId());

        Event sell = trace.events().get(1);
        assertEquals(EventKind.SELL, sell.kind());
        assertEquals(new BigDecimal("8"), sell.quantity());
        assertEquals(0, sell.quantityChange().compareTo(new BigDecimal("-5")));
        assertEquals(0, sell.quantityAfter().signum());
        assertTrue(trace.openLots().isEmpty());
    }

    @Test
    void intradayNettedDayTracesTheNettingAndTheDeliveryResidualLot() {
        LocalDate day = LocalDate.of(2024, 1, 10);
        stubHolding(List.of(
                trade(InvestmentTransactionType.buy, "10", "100", day),
                trade(InvestmentTransactionType.buy, "5", "102", day),
                trade(InvestmentTransactionType.sell, "10", "105", day)),
                List.of());
        when(classificationRepository.findByHoldingId(holding.getId())).thenReturn(List.of(
                new TradeSettlementClassification(owner, brokerAccount, holding, instrument, day,
                        new BigDecimal("10"), new BigDecimal("1000"), new BigDecimal("1050"))));

        HoldingTrace trace = investmentService.traceHoldingPosition(holding.getId());

        assertEquals(investmentService.calculateHoldingPosition(holding), trace.position());
        assertEquals(2, trace.events().size());
        Event netted = trace.events().get(0);
        assertEquals(EventKind.INTRADAY_NETTED, netted.kind());
        assertEquals(new BigDecimal("10"), netted.quantity());
        assertNull(netted.price());
        assertEquals(0, netted.quantityChange().signum());
        assertEquals(0, netted.quantityAfter().signum());
        Event delivery = trace.events().get(1);
        assertEquals(EventKind.DELIVERY_BUY, delivery.kind());
        assertEquals(0, delivery.quantity().compareTo(new BigDecimal("5")));
        assertEquals(new BigDecimal("102.00000000"), delivery.price());
        assertEquals(0, delivery.quantityAfter().compareTo(new BigDecimal("5")));
        assertEquals(LotOrigin.INTRADAY_NETTED_DELIVERY, trace.openLots().get(0).source().origin());
        assertLotsSumToPosition(trace);
    }

    @Test
    void intradayNettedSellResidualIsTracedAsADeliverySell() {
        LocalDate buyDay = LocalDate.of(2024, 1, 2);
        LocalDate day = LocalDate.of(2024, 1, 10);
        stubHolding(List.of(
                trade(InvestmentTransactionType.buy, "20", "90", buyDay),
                trade(InvestmentTransactionType.buy, "10", "100", day),
                trade(InvestmentTransactionType.sell, "14", "105", day)),
                List.of());
        when(classificationRepository.findByHoldingId(holding.getId())).thenReturn(List.of(
                new TradeSettlementClassification(owner, brokerAccount, holding, instrument, day,
                        new BigDecimal("10"), new BigDecimal("1000"), new BigDecimal("1050"))));

        HoldingTrace trace = investmentService.traceHoldingPosition(holding.getId());

        Event delivery = trace.events().get(2);
        assertEquals(EventKind.DELIVERY_SELL, delivery.kind());
        assertEquals(0, delivery.quantityChange().compareTo(new BigDecimal("-4")));
        assertEquals(0, delivery.quantityAfter().compareTo(new BigDecimal("16")));
        assertEquals(LotOrigin.BUY, trace.openLots().get(0).source().origin());
        assertLotsSumToPosition(trace);
    }

    // ---- corporate actions on this instrument ----

    @Test
    void splitAndBonusTraceTheQuantityTheyAdd() {
        CorporateAction split = corporateAction(CorporateActionType.split, 1, 2, LocalDate.of(2024, 3, 1));
        CorporateAction bonus = corporateAction(CorporateActionType.bonus, 1, 2, LocalDate.of(2024, 6, 1));
        stubHolding(List.of(trade(InvestmentTransactionType.buy, "10", "100", LocalDate.of(2024, 1, 1))),
                List.of(split, bonus));

        HoldingTrace trace = investmentService.traceHoldingPosition(holding.getId());

        Event splitEvent = trace.events().get(1);
        assertEquals(EventKind.CORPORATE_ACTION, splitEvent.kind());
        assertSame(split, splitEvent.action());
        assertNull(splitEvent.quantity());
        assertNull(splitEvent.price());
        assertEquals(0, splitEvent.quantityChange().compareTo(new BigDecimal("10")));
        assertEquals(0, splitEvent.quantityAfter().compareTo(new BigDecimal("20")));
        Event bonusEvent = trace.events().get(2);
        assertSame(bonus, bonusEvent.action());
        assertEquals(0, bonusEvent.quantityChange().compareTo(new BigDecimal("20")));
        assertEquals(0, bonusEvent.quantityAfter().compareTo(new BigDecimal("40")));
        // The split rescales the bought lot (20 @ 50); the bonus adds a new zero-cost lot on its ex-date.
        assertEquals(0, trace.openLots().get(0).costPerUnit().compareTo(new BigDecimal("50")));
        assertEquals(0, trace.openLots().get(1).costPerUnit().signum());
        assertEquals(LocalDate.of(2024, 6, 1), trace.openLots().get(1).buyDate());
        assertEquals(LotOrigin.BONUS, trace.openLots().get(1).source().origin());
        assertLotsSumToPosition(trace);
    }

    @Test
    void sameDayCorporateActionIsTracedBeforeTheTradeAsTheEngineAppliesIt() {
        LocalDate day = LocalDate.of(2024, 3, 1);
        CorporateAction split = corporateAction(CorporateActionType.split, 1, 2, day);
        stubHolding(List.of(trade(InvestmentTransactionType.buy, "10", "100", day)), List.of(split));

        HoldingTrace trace = investmentService.traceHoldingPosition(holding.getId());

        assertEquals(EventKind.CORPORATE_ACTION, trace.events().get(0).kind());
        assertEquals(0, trace.events().get(0).quantityAfter().signum());
        assertEquals(EventKind.BUY, trace.events().get(1).kind());
        assertEquals(new BigDecimal("10"), trace.openLots().get(0).quantity());
    }

    @Test
    void demergerOfThisInstrumentCarvesCostWithoutChangingQuantity() {
        CorporateAction demerger = corporateAction(CorporateActionType.demerger, 2, 1, LocalDate.of(2024, 6, 1));
        demerger.setCostAllocationPct(new BigDecimal("20"));
        demerger.setTargetInstrument(instrument("Child SpinCo"));
        stubHolding(List.of(trade(InvestmentTransactionType.buy, "100", "100", LocalDate.of(2024, 1, 1))),
                List.of(demerger));

        HoldingTrace trace = investmentService.traceHoldingPosition(holding.getId());

        Event event = trace.events().get(1);
        assertSame(demerger, event.action());
        assertEquals(0, event.quantityChange().signum());
        assertEquals(0, event.quantityAfter().compareTo(new BigDecimal("100")));
        assertEquals(new BigDecimal("80.00000000"), trace.openLots().get(0).costPerUnit());
        assertLotsSumToPosition(trace);
    }

    @Test
    void mergerOfThisInstrumentClosesEveryLot() {
        CorporateAction merger = corporateAction(CorporateActionType.merger, 25, 42, LocalDate.of(2024, 6, 1));
        merger.setTargetInstrument(instrument("HDFC Bank"));
        stubHolding(List.of(trade(InvestmentTransactionType.buy, "100", "100", LocalDate.of(2024, 1, 1))),
                List.of(merger));

        HoldingTrace trace = investmentService.traceHoldingPosition(holding.getId());

        Event event = trace.events().get(1);
        assertSame(merger, event.action());
        assertEquals(0, event.quantityChange().compareTo(new BigDecimal("-100")));
        assertEquals(0, event.quantityAfter().signum());
        assertTrue(trace.openLots().isEmpty());
        assertEquals(investmentService.calculateHoldingPosition(holding), trace.position());
    }

    // ---- shares received from another instrument ----

    @Test
    void sharesReceivedFromADemergerAreTracedWithTheirSourceAction() {
        Instrument parent = instrument("Parent Corp");
        Holding parentHolding = holding(parent);
        CorporateAction demerger = corporateAction(CorporateActionType.demerger, 2, 1, LocalDate.of(2024, 6, 1));
        demerger.setInstrument(parent);
        demerger.setTargetInstrument(instrument);
        demerger.setCostAllocationPct(new BigDecimal("20"));

        stubHolding(List.of(), List.of());
        when(corporateActionRepository.findByUser_IdAndTargetInstrument_IdOrderByExDateAsc(owner.getId(), instrument.getId())).thenReturn(List.of(demerger));
        when(holdingRepository.findByBrokerAccountIdAndInstrumentId(brokerAccount.getId(), parent.getId()))
                .thenReturn(Optional.of(parentHolding));
        when(transactionRepository.findByHoldingIdOrderByTradeDateAscCreatedAtAsc(parentHolding.getId()))
                .thenReturn(List.of(trade(InvestmentTransactionType.buy, "100", "100", LocalDate.of(2024, 1, 1))));
        when(corporateActionRepository.findByUser_IdAndInstrument_IdOrderByExDateAsc(owner.getId(), parent.getId())).thenReturn(List.of(demerger));

        HoldingTrace trace = investmentService.traceHoldingPosition(holding.getId());

        assertEquals(List.of(new Event(LocalDate.of(2024, 6, 1), EventKind.RECEIVED_FROM_CORPORATE_ACTION, demerger,
                new BigDecimal("50.00000000"), null, new BigDecimal("50.00000000"), new BigDecimal("50.00000000"))),
                trace.events());
        OpenLot lot = trace.openLots().get(0);
        assertEquals(LotOrigin.CORPORATE_ACTION, lot.source().origin());
        assertSame(demerger, lot.source().action());
        // The child shares inherit the parent lot's buy date (s.2(42A)); the event stays on the ex-date.
        assertEquals(LocalDate.of(2024, 1, 1), lot.buyDate());
        assertLotsSumToPosition(trace);
        assertSame(demerger, investmentService.seedLotsFor(holding).get(0).source());
    }

    // ---- helpers ----

    /** Σ lot quantity / cost, unrounded, round to exactly the position's quantity / invested. */
    private static void assertLotsSumToPosition(HoldingTrace trace) {
        BigDecimal qty = BigDecimal.ZERO;
        BigDecimal cost = BigDecimal.ZERO;
        for (OpenLot lot : trace.openLots()) {
            qty = qty.add(lot.quantity());
            cost = cost.add(lot.cost());
        }
        assertEquals(trace.position().openQty(), qty.setScale(8, RoundingMode.HALF_UP));
        assertEquals(trace.position().openCost(), cost.setScale(4, RoundingMode.HALF_UP));
    }

    private void stubHolding(List<InvestmentTransaction> txns, List<CorporateAction> corporateActions) {
        when(holdingRepository.findById(holding.getId())).thenReturn(Optional.of(holding));
        when(transactionRepository.findByHoldingIdOrderByTradeDateAscCreatedAtAsc(holding.getId())).thenReturn(txns);
        when(corporateActionRepository.findByUser_IdAndInstrument_IdOrderByExDateAsc(owner.getId(), instrument.getId())).thenReturn(corporateActions);
        when(priceRepository.findLatestVisible(org.mockito.ArgumentMatchers.eq(instrument.getId()), org.mockito.ArgumentMatchers.any())).thenReturn(java.util.List.of());
    }

    private Instrument instrument(String name) {
        Instrument i = new Instrument();
        i.setId(UUID.randomUUID());
        i.setName(name);
        i.setType(InstrumentType.stock);
        return i;
    }

    private Holding holding(Instrument i) {
        Holding h = new Holding(brokerAccount, i, null);
        h.setId(UUID.randomUUID());
        h.setUser(owner);
        return h;
    }

    private CorporateAction corporateAction(CorporateActionType type, int ratioFrom, int ratioTo, LocalDate exDate) {
        CorporateAction ca = new CorporateAction();
        ca.setId(UUID.randomUUID());
        ca.setInstrument(instrument);
        ca.setType(type);
        ca.setRatioFrom(ratioFrom);
        ca.setRatioTo(ratioTo);
        ca.setExDate(exDate);
        return ca;
    }

    private static InvestmentTransaction trade(InvestmentTransactionType type, String qty, String price, LocalDate date) {
        InvestmentTransaction t = new InvestmentTransaction();
        t.setType(type);
        t.setQuantity(new BigDecimal(qty));
        t.setPrice(new BigDecimal(price));
        t.setTradeDate(date);
        return t;
    }
}
