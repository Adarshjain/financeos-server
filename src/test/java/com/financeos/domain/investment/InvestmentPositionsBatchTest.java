package com.financeos.domain.investment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.financeos.api.investment.dto.PositionDto;
import com.financeos.api.investment.dto.SummaryResponse;
import com.financeos.core.security.UserContext;
import com.financeos.core.time.AppTime;
import com.financeos.domain.account.Account;
import com.financeos.domain.holding.Holding;
import com.financeos.domain.holding.HoldingRepository;
import com.financeos.domain.instrument.AssetClassOverrideService;
import com.financeos.domain.instrument.Instrument;
import com.financeos.domain.instrument.InstrumentPrice;
import com.financeos.domain.instrument.InstrumentPriceRepository;
import com.financeos.domain.instrument.InstrumentType;
import com.financeos.domain.instrument.PriceSource;
import com.financeos.domain.instrument.corporateaction.CorporateAction;
import com.financeos.domain.instrument.corporateaction.CorporateActionRepository;
import com.financeos.domain.instrument.corporateaction.CorporateActionType;
import com.financeos.domain.investment.dividend.Dividend;
import com.financeos.domain.investment.dividend.DividendRepository;
import com.financeos.domain.investment.dto.RealizedLot;
import com.financeos.domain.user.User;
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

/**
 * Positions, summary and realised lots read the lot engine's inputs once for all holdings (a split,
 * an intraday-netted day, dividends and prices included) and produce what the per-holding reads do.
 */
class InvestmentPositionsBatchTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 9);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private HoldingRepository holdingRepository;
    private InvestmentTransactionRepository transactionRepository;
    private InstrumentPriceRepository priceRepository;
    private CorporateActionRepository corporateActionRepository;
    private DividendRepository dividendRepository;
    private TradeSettlementClassificationRepository classificationRepository;
    private InvestmentService service;
    private Account broker;
    private User owner;

    private Holding splitHolding;
    private Holding intradayHolding;
    private Holding plainHolding;

    @BeforeEach
    void setUp() {
        AppTime.useClock(Clock.fixed(TODAY.atStartOfDay(IST).toInstant(), IST));
        holdingRepository = mock(HoldingRepository.class);
        transactionRepository = mock(InvestmentTransactionRepository.class);
        priceRepository = mock(InstrumentPriceRepository.class);
        corporateActionRepository = mock(CorporateActionRepository.class);
        dividendRepository = mock(DividendRepository.class);
        classificationRepository = mock(TradeSettlementClassificationRepository.class);
        service = new InvestmentService(transactionRepository, holdingRepository,
                mock(com.financeos.domain.account.AccountRepository.class),
                mock(com.financeos.domain.instrument.InstrumentRepository.class), priceRepository,
                mock(com.financeos.domain.user.UserRepository.class), corporateActionRepository, dividendRepository,
                classificationRepository, mock(org.springframework.context.ApplicationEventPublisher.class),
                mock(com.financeos.domain.investment.fno.FnoTradeRepository.class), mock(AssetClassOverrideService.class));
        broker = new Account();
        broker.setId(UUID.randomUUID());
        broker.setName("Zerodha");
        owner = new User();
        owner.setId(UUID.randomUUID());
        UserContext.setCurrentUserId(owner.getId());

        splitHolding = holding("SPLITCO");
        intradayHolding = holding("DAYCO");
        plainHolding = holding("PLAINCO");
        when(holdingRepository.findAllWithDetails()).thenReturn(List.of(splitHolding, intradayHolding, plainHolding));

        InvestmentTransaction splitBuy = txn(splitHolding, InvestmentTransactionType.buy, "10", "100", LocalDate.of(2024, 1, 1));
        InvestmentTransaction splitSell = txn(splitHolding, InvestmentTransactionType.sell, "5", "70", LocalDate.of(2025, 1, 1));
        LocalDate day = LocalDate.of(2026, 9, 1);
        InvestmentTransaction dayBuy = txn(intradayHolding, InvestmentTransactionType.buy, "10", "100", day);
        InvestmentTransaction daySell = txn(intradayHolding, InvestmentTransactionType.sell, "10", "105", day);
        InvestmentTransaction dayEarlier = txn(intradayHolding, InvestmentTransactionType.buy, "4", "90", LocalDate.of(2026, 1, 1));
        InvestmentTransaction plainBuy = txn(plainHolding, InvestmentTransactionType.buy, "3", "200", LocalDate.of(2025, 5, 5));
        InvestmentTransaction plainSell = txn(plainHolding, InvestmentTransactionType.sell, "1", "260", LocalDate.of(2026, 2, 2));

        CorporateAction split = new CorporateAction();
        split.setId(UUID.randomUUID());
        split.setUser(owner);
        split.setInstrument(splitHolding.getInstrument());
        split.setType(CorporateActionType.split);
        split.setRatioFrom(1);
        split.setRatioTo(2);
        split.setExDate(LocalDate.of(2024, 3, 1));

        TradeSettlementClassification intraday = new TradeSettlementClassification(owner, broker, intradayHolding,
                intradayHolding.getInstrument(), day, new BigDecimal("10"), new BigDecimal("1000"), new BigDecimal("1050"));

        Dividend dividend = new Dividend();
        dividend.setHolding(plainHolding);
        dividend.setAmount(new BigDecimal("12"));
        dividend.setPayDate(LocalDate.of(2026, 3, 3));

        List<InstrumentPrice> prices = List.of(
                new InstrumentPrice(splitHolding.getInstrument(), TODAY, new BigDecimal("60"), PriceSource.YAHOO),
                new InstrumentPrice(intradayHolding.getInstrument(), TODAY, new BigDecimal("95"), PriceSource.YAHOO),
                new InstrumentPrice(plainHolding.getInstrument(), TODAY, new BigDecimal("250"), PriceSource.YAHOO));

        // the batch reads (what the list endpoints use)
        when(transactionRepository.findByUser_IdOrderByTradeDateAscCreatedAtAsc(owner.getId()))
                .thenReturn(List.of(splitBuy, splitSell, dayEarlier, dayBuy, daySell, plainBuy, plainSell));
        when(corporateActionRepository.findOwnedByInstrumentIds(eq(owner.getId()), anyCollection())).thenReturn(List.of(split));
        when(classificationRepository.findByUser_Id(owner.getId())).thenReturn(List.of(intraday));
        when(dividendRepository.findByUser_Id(owner.getId())).thenReturn(List.of(dividend));
        when(priceRepository.findLatestByInstrumentIds(anyCollection(), any())).thenReturn(prices);

        // the same data through the per-holding reads (what a single holding uses)
        when(transactionRepository.findByHoldingIdOrderByTradeDateAscCreatedAtAsc(splitHolding.getId()))
                .thenReturn(List.of(splitBuy, splitSell));
        when(transactionRepository.findByHoldingIdOrderByTradeDateAscCreatedAtAsc(intradayHolding.getId()))
                .thenReturn(List.of(dayEarlier, dayBuy, daySell));
        when(transactionRepository.findByHoldingIdOrderByTradeDateAscCreatedAtAsc(plainHolding.getId()))
                .thenReturn(List.of(plainBuy, plainSell));
        when(corporateActionRepository.findByUser_IdAndInstrument_IdOrderByExDateAsc(owner.getId(),
                splitHolding.getInstrument().getId())).thenReturn(List.of(split));
        when(classificationRepository.findByHoldingId(intradayHolding.getId())).thenReturn(List.of(intraday));
        when(dividendRepository.sumAmountByHoldingId(plainHolding.getId())).thenReturn(new BigDecimal("12"));
        when(dividendRepository.findByHoldingIdOrderByPayDateDescCreatedAtDesc(plainHolding.getId()))
                .thenReturn(List.of(dividend));
        for (int i = 0; i < prices.size(); i++) {
            InstrumentPrice p = prices.get(i);
            when(priceRepository.findLatestVisible(eq(p.getInstrument().getId()), any())).thenReturn(List.of(p));
        }
    }

    @AfterEach
    void tearDown() {
        AppTime.reset();
        UserContext.clear();
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

    private static InvestmentTransaction txn(Holding h, InvestmentTransactionType type, String qty, String price,
                                             LocalDate date) {
        InvestmentTransaction t = new InvestmentTransaction();
        t.setId(UUID.randomUUID());
        t.setHolding(h);
        t.setType(type);
        t.setQuantity(new BigDecimal(qty));
        t.setPrice(new BigDecimal(price));
        t.setTradeDate(date);
        return t;
    }

    private void verifyNoPerHoldingReads() {
        verify(transactionRepository, never()).findByHoldingIdOrderByTradeDateAscCreatedAtAsc(any());
        verify(corporateActionRepository, never()).findByUser_IdAndInstrument_IdOrderByExDateAsc(any(), any());
        verify(classificationRepository, never()).findByHoldingId(any());
        verify(dividendRepository, never()).sumAmountByHoldingId(any());
        verify(dividendRepository, never()).findByHoldingIdOrderByPayDateDescCreatedAtDesc(any());
        verify(priceRepository, never()).findLatestVisible(any(), any());
    }

    @Test
    void positionsEqualThePerHoldingEngineAndReadEachInputOnce() {
        List<PositionDto> positions = service.getAllPositions();

        verify(transactionRepository, times(1)).findByUser_IdOrderByTradeDateAscCreatedAtAsc(owner.getId());
        verify(classificationRepository, times(1)).findByUser_Id(owner.getId());
        verify(dividendRepository, times(1)).findByUser_Id(owner.getId());
        verify(corporateActionRepository, times(1)).findOwnedByInstrumentIds(eq(owner.getId()), anyCollection());
        verify(priceRepository, times(1)).findLatestByInstrumentIds(anyCollection(), any());
        verifyNoPerHoldingReads();

        assertEquals(3, positions.size());
        for (PositionDto actual : positions) {
            Holding h = List.of(splitHolding, intradayHolding, plainHolding).stream()
                    .filter(x -> x.getId().equals(actual.holdingId())).findFirst().orElseThrow();
            PositionDto expected = service.calculateHoldingPosition(h).toPositionDto(
                    com.financeos.domain.instrument.InstrumentOverrides.NONE);
            assertEquals(expected.quantity(), actual.quantity());
            assertEquals(expected.avgCost(), actual.avgCost());
            assertEquals(expected.invested(), actual.invested());
            assertEquals(expected.lastPrice(), actual.lastPrice());
            assertEquals(expected.currentValue(), actual.currentValue());
            assertEquals(expected.realizedGainLoss(), actual.realizedGainLoss());
            assertEquals(expected.intradayRealized(), actual.intradayRealized());
            assertEquals(expected.dividends(), actual.dividends());
            assertEquals(expected.totalCharges(), actual.totalCharges());
            assertEquals(expected.xirr(), actual.xirr());
        }
        PositionDto split = positions.get(0);
        assertEquals(0, new BigDecimal("15").compareTo(split.quantity()), "10 split 1:2 = 20, minus 5 sold");
        assertEquals(0, new BigDecimal("50").compareTo(positions.get(1).intradayRealized()), "1050 - 1000");
        assertEquals(0, new BigDecimal("12").compareTo(positions.get(2).dividends()));
    }

    @Test
    void summaryTotalsEqualThePerHoldingEngineAndReadEachInputOnce() {
        SummaryResponse summary = service.getSummary();

        verify(transactionRepository, times(1)).findByUser_IdOrderByTradeDateAscCreatedAtAsc(owner.getId());
        verify(priceRepository, times(1)).findLatestByInstrumentIds(anyCollection(), any());
        verifyNoPerHoldingReads();

        BigDecimal realized = BigDecimal.ZERO;
        BigDecimal intraday = BigDecimal.ZERO;
        BigDecimal invested = BigDecimal.ZERO;
        for (Holding h : List.of(splitHolding, intradayHolding, plainHolding)) {
            HoldingPosition p = service.calculateHoldingPosition(h);
            realized = realized.add(p.realized());
            intraday = intraday.add(p.intradayRealized());
            invested = invested.add(p.openCost());
        }
        assertEquals(0, realized.compareTo(summary.totalRealized()));
        assertEquals(0, intraday.compareTo(summary.totalIntradayRealized()));
        assertEquals(0, invested.compareTo(summary.totalInvested()));
    }

    @Test
    void realisedLotsEqualThePerHoldingEngineAndReadEachInputOnce() {
        List<RealizedLot> lots = service.getAllRealizedLots();

        verify(transactionRepository, times(1)).findByUser_IdOrderByTradeDateAscCreatedAtAsc(owner.getId());
        verifyNoPerHoldingReads();

        List<RealizedLot> expected = new ArrayList<>();
        for (Holding h : List.of(splitHolding, intradayHolding, plainHolding)) {
            service.calculateHoldingPosition(h, expected::add);
        }
        assertNotNull(lots);
        assertEquals(expected.size(), lots.size());
        for (int i = 0; i < lots.size(); i++) {
            assertEquals(0, expected.get(i).realizedPnl().compareTo(lots.get(i).realizedPnl()));
            assertEquals(expected.get(i).term(), lots.get(i).term());
            assertEquals(0, expected.get(i).quantity().compareTo(lots.get(i).quantity()));
        }
        assertEquals(2, lots.size(), "a realised lot for the split and the plain holding");
    }
}
