package com.financeos.domain.investment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.financeos.api.investment.dto.PositionDto;
import com.financeos.api.investment.dto.SummaryResponse;
import com.financeos.core.security.UserContext;
import com.financeos.core.time.AppTime;
import com.financeos.domain.account.Account;
import com.financeos.domain.holding.Holding;
import com.financeos.domain.holding.HoldingRepository;
import com.financeos.domain.instrument.AssetClass;
import com.financeos.domain.instrument.AssetClassOverrideService;
import com.financeos.domain.instrument.Instrument;
import com.financeos.domain.instrument.InstrumentOverrides;
import com.financeos.domain.instrument.InstrumentPrice;
import com.financeos.domain.instrument.InstrumentPriceRepository;
import com.financeos.domain.instrument.InstrumentType;
import com.financeos.domain.instrument.PriceSource;
import com.financeos.domain.instrument.TaxClass;
import com.financeos.domain.instrument.UserInstrumentOverride;
import com.financeos.domain.instrument.corporateaction.CorporateAction;
import com.financeos.domain.instrument.corporateaction.CorporateActionRepository;
import com.financeos.domain.instrument.corporateaction.CorporateActionType;
import com.financeos.domain.investment.dividend.Dividend;
import com.financeos.domain.investment.dividend.DividendRepository;
import com.financeos.domain.investment.dto.RealizedLot;
import com.financeos.domain.user.User;
import java.math.BigDecimal;
import java.sql.Date;
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
 * InvestmentService: the current user's asset-class overrides on positions, realised lots and holding
 * lots; split-aware / staleness-gated day change on positions and the summary; and the tax-harvest
 * lot engine reading its inputs in a constant number of batch queries.
 */
class InvestmentOverridesAndBatchTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 9);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final String LIQUID = "Open Ended Schemes(Debt Scheme - Liquid Fund)";

    private HoldingRepository holdingRepository;
    private InvestmentTransactionRepository transactionRepository;
    private InstrumentPriceRepository priceRepository;
    private CorporateActionRepository corporateActionRepository;
    private DividendRepository dividendRepository;
    private TradeSettlementClassificationRepository classificationRepository;
    private AssetClassOverrideService overrides;
    private InvestmentService service;
    private Account broker;
    private User owner;

    @BeforeEach
    void setUp() {
        AppTime.useClock(Clock.fixed(TODAY.atStartOfDay(IST).toInstant(), IST));
        holdingRepository = mock(HoldingRepository.class);
        transactionRepository = mock(InvestmentTransactionRepository.class);
        priceRepository = mock(InstrumentPriceRepository.class);
        corporateActionRepository = mock(CorporateActionRepository.class);
        dividendRepository = mock(DividendRepository.class);
        classificationRepository = mock(TradeSettlementClassificationRepository.class);
        overrides = mock(AssetClassOverrideService.class);
        service = new InvestmentService(transactionRepository, holdingRepository,
                mock(com.financeos.domain.account.AccountRepository.class),
                mock(com.financeos.domain.instrument.InstrumentRepository.class), priceRepository,
                mock(com.financeos.domain.user.UserRepository.class), corporateActionRepository, dividendRepository,
                classificationRepository, mock(org.springframework.context.ApplicationEventPublisher.class),
                mock(com.financeos.domain.investment.fno.FnoTradeRepository.class), overrides);
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

    private Holding holding(InstrumentType type, String name, String category) {
        Instrument instrument = new Instrument();
        instrument.setId(UUID.randomUUID());
        instrument.setType(type);
        instrument.setName(name);
        instrument.setSchemeCategory(category);
        Holding h = new Holding(broker, instrument, null);
        h.setId(UUID.randomUUID());
        h.setUser(owner);
        return h;
    }

    private InstrumentOverrides overrideOf(Holding h, AssetClass assetClass) {
        return InstrumentOverrides.of(List.of(new UserInstrumentOverride(owner.getId(), h.getInstrument().getId(), assetClass)));
    }

    private static InvestmentTransaction txn(Holding h, InvestmentTransactionType type, String qty, String price,
                                             LocalDate date) {
        InvestmentTransaction t = new InvestmentTransaction();
        t.setHolding(h);
        t.setType(type);
        t.setQuantity(d(qty));
        t.setPrice(d(price));
        t.setTradeDate(date);
        return t;
    }

    private void priced(Holding h, String qty, LocalDate asOf, String close) {
        when(transactionRepository.findByHoldingIdOrderByTradeDateAscCreatedAtAsc(h.getId())).thenReturn(List.of(
                txn(h, InvestmentTransactionType.buy, qty, "90", LocalDate.of(2026, 1, 5))));
        when(priceRepository.findLatestVisible(org.mockito.ArgumentMatchers.eq(h.getInstrument().getId()), org.mockito.ArgumentMatchers.any())).thenReturn(java.util.List.of(
                new InstrumentPrice(h.getInstrument(), asOf, d(close), PriceSource.YAHOO)));
    }

    private static Object[] close(Holding h, LocalDate asOf, String close) {
        return new Object[]{h.getInstrument().getId().toString(), Date.valueOf(asOf), d(close)};
    }

    // ------------------------------------------------------------------ overrides

    @Test
    void positionsCarryTheCurrentUsersOverride() {
        Holding fund = holding(InstrumentType.mutual_fund, "Liquid", LIQUID);
        Holding stock = holding(InstrumentType.stock, "INFY", null);
        when(holdingRepository.findAllWithDetails()).thenReturn(List.of(fund, stock));
        priced(fund, "1", TODAY, "100");
        priced(stock, "1", TODAY, "100");
        when(overrides.overridesFor(owner.getId())).thenReturn(overrideOf(fund, AssetClass.GOLD));

        List<PositionDto> positions = service.getAllPositions();

        assertEquals(AssetClass.GOLD, positions.get(0).assetClass());
        assertEquals(TaxClass.OTHER, positions.get(0).taxClass());
        assertEquals(AssetClass.EQUITY, positions.get(1).assetClass(), "no override: the global class");
        verify(overrides, times(1)).overridesFor(owner.getId());
    }

    @Test
    void realisedLotsUseTheOverrideForTheirTerm() {
        Holding fund = holding(InstrumentType.mutual_fund, "Liquid", LIQUID);
        when(holdingRepository.findAllWithDetails()).thenReturn(List.of(fund));
        when(transactionRepository.findByHoldingIdOrderByTradeDateAscCreatedAtAsc(fund.getId())).thenReturn(List.of(
                txn(fund, InvestmentTransactionType.buy, "10", "100", LocalDate.of(2024, 1, 1)),
                txn(fund, InvestmentTransactionType.sell, "10", "120", LocalDate.of(2025, 6, 1))));
        when(overrides.overridesFor(owner.getId())).thenReturn(overrideOf(fund, AssetClass.EQUITY));

        RealizedLot lot = service.getAllRealizedLots().get(0);

        assertEquals(AssetClass.EQUITY, lot.assetClass());
        assertEquals(TaxClass.EQUITY_ORIENTED, lot.taxClass());
        assertEquals("long", lot.term(), "equity after 12 months, not slab debt");
    }

    @Test
    void aSingleHoldingsRealisedLotsLookUpThatOneOverride() {
        Holding fund = holding(InstrumentType.mutual_fund, "Liquid", LIQUID);
        when(transactionRepository.findByHoldingIdOrderByTradeDateAscCreatedAtAsc(fund.getId())).thenReturn(List.of(
                txn(fund, InvestmentTransactionType.buy, "10", "100", LocalDate.of(2024, 1, 1)),
                txn(fund, InvestmentTransactionType.sell, "10", "120", LocalDate.of(2025, 6, 1))));
        when(overrides.overridesFor(owner.getId())).thenReturn(overrideOf(fund, AssetClass.INTERNATIONAL));

        List<RealizedLot> lots = new ArrayList<>();
        service.calculateHoldingPosition(fund, lots::add);

        assertEquals(AssetClass.INTERNATIONAL, lots.get(0).assetClass());
        assertEquals("short", lots.get(0).term(), "international is long only after 24 months");
        // Without a collector nothing needs the class: no lookup.
        service.calculateHoldingPosition(fund);
        verify(overrides, times(1)).overridesFor(owner.getId());
    }

    // ------------------------------------------------------------------ day change

    @Test
    void positionsAndSummaryAdjustForASplitAndSkipStalePrices() {
        Holding split = holding(InstrumentType.stock, "SPLIT", null);
        Holding stale = holding(InstrumentType.stock, "STALE", null);
        when(holdingRepository.findAllWithDetails()).thenReturn(List.of(split, stale));
        priced(split, "20", LocalDate.of(2026, 10, 8), "510");
        priced(stale, "10", LocalDate.of(2026, 10, 1), "120");
        when(priceRepository.findLatestTwoCloses(anyCollection(), org.mockito.ArgumentMatchers.any())).thenReturn(List.of(
                close(split, LocalDate.of(2026, 10, 8), "510"), close(split, LocalDate.of(2026, 10, 7), "1000"),
                close(stale, LocalDate.of(2026, 10, 1), "120"), close(stale, LocalDate.of(2026, 9, 30), "100")));
        CorporateAction ca = new CorporateAction();
        ca.setInstrument(split.getInstrument());
        ca.setType(CorporateActionType.split);
        ca.setRatioFrom(1);
        ca.setRatioTo(2);
        ca.setExDate(LocalDate.of(2026, 10, 8));
        when(corporateActionRepository.findByInstrumentIdsExDateFrom(anyCollection(), eq(TODAY.minusDays(11))))
                .thenReturn(List.of(ca));

        List<PositionDto> positions = service.getAllPositions();
        assertEquals(0, d("500").compareTo(positions.get(0).previousClose()));
        assertEquals(d("200.00"), positions.get(0).dayChange());
        assertEquals(d("2.00"), positions.get(0).dayChangePct());
        assertNull(positions.get(1).dayChange(), "a price from 8 days ago is not today's move");
        assertNull(positions.get(1).previousClose());

        SummaryResponse summary = service.getSummary();
        assertEquals(d("200.00"), summary.dayChange());
        assertEquals(d("2.00"), summary.dayChangePct(), "over the adjusted previous value 20 × 500");
        assertEquals(LocalDate.of(2026, 10, 7), summary.previousPriceAsOf());
        verify(corporateActionRepository, times(2)).findByInstrumentIdsExDateFrom(anyCollection(), any());
    }

    // ------------------------------------------------------------------ batch engine inputs

    @Test
    void holdingLotsReadEverythingInAConstantNumberOfQueries() {
        int n = 12;
        List<Holding> holdings = new ArrayList<>();
        List<InvestmentTransaction> txns = new ArrayList<>();
        List<InstrumentPrice> prices = new ArrayList<>();
        List<Dividend> dividends = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Holding h = holding(InstrumentType.etf, "NIFTY ETF " + i, null);
            holdings.add(h);
            txns.add(txn(h, InvestmentTransactionType.buy, "10", "100", LocalDate.of(2024, 1, 1)));
            txns.add(txn(h, InvestmentTransactionType.sell, "4", "150", LocalDate.of(2026, 5, 1)));
            prices.add(new InstrumentPrice(h.getInstrument(), TODAY, d("200"), PriceSource.YAHOO));
            Dividend div = new Dividend();
            div.setHolding(h);
            div.setAmount(d("5"));
            div.setPayDate(LocalDate.of(2025, 1, 1));
            dividends.add(div);
        }
        when(holdingRepository.findAllWithDetails()).thenReturn(holdings);
        when(transactionRepository.findByUser_IdOrderByTradeDateAscCreatedAtAsc(owner.getId())).thenReturn(txns);
        when(priceRepository.findLatestByInstrumentIds(anyCollection(), org.mockito.ArgumentMatchers.any())).thenReturn(prices);
        when(dividendRepository.findByUser_Id(owner.getId())).thenReturn(dividends);
        when(overrides.overridesFor(owner.getId())).thenReturn(overrideOf(holdings.get(0), AssetClass.GOLD));

        List<InvestmentService.HoldingLots> lots = service.getAllHoldingLots();

        assertEquals(n, lots.size());
        InvestmentService.HoldingLots first = lots.get(0);
        assertEquals(1, first.realizedLots().size());
        assertEquals(0, d("200").compareTo(first.realizedLots().get(0).realizedPnl()));   // 4 × (150 − 100)
        assertEquals(1, first.openLots().size());
        assertEquals(0, d("6").compareTo(first.openLots().get(0).quantity()));
        assertEquals(0, d("200").compareTo(first.position().latestPrice()));
        assertEquals(0, d("5").compareTo(first.position().dividends()));
        assertEquals(AssetClass.GOLD, first.classification().assetClass(), "the user's override");
        assertEquals(TaxClass.OTHER, first.realizedLots().get(0).taxClass());
        assertEquals(AssetClass.EQUITY, lots.get(1).classification().assetClass());

        verify(holdingRepository, times(1)).findAllWithDetails();
        verify(transactionRepository, times(1)).findByUser_IdOrderByTradeDateAscCreatedAtAsc(owner.getId());
        verify(classificationRepository, times(1)).findByUser_Id(owner.getId());
        verify(dividendRepository, times(1)).findByUser_Id(owner.getId());
        verify(corporateActionRepository, times(1)).findByInstrumentIdsWithInstruments(anyCollection());
        verify(corporateActionRepository, times(1)).findByTargetInstrumentIdsWithInstruments(anyCollection());
        verify(priceRepository, times(1)).findLatestByInstrumentIds(anyCollection(), org.mockito.ArgumentMatchers.any());
        verify(overrides, times(1)).overridesFor(owner.getId());
        verifyNoMoreInteractions(holdingRepository, transactionRepository, classificationRepository, dividendRepository,
                corporateActionRepository, priceRepository, overrides);
        verify(transactionRepository, never()).findByHoldingIdOrderByTradeDateAscCreatedAtAsc(any());
    }

    @Test
    void holdingLotsSeedAMergerFromTheBatchWithoutPerHoldingQueries() {
        Holding parent = holding(InstrumentType.stock, "Parent", null);
        Holding acquirer = holding(InstrumentType.stock, "Acquirer", null);
        CorporateAction merger = new CorporateAction();
        merger.setId(UUID.randomUUID());
        merger.setInstrument(parent.getInstrument());
        merger.setTargetInstrument(acquirer.getInstrument());
        merger.setType(CorporateActionType.merger);
        merger.setRatioFrom(1);
        merger.setRatioTo(2);
        merger.setCostAllocationPct(d("100"));
        merger.setExDate(LocalDate.of(2024, 6, 1));
        when(holdingRepository.findAllWithDetails()).thenReturn(List.of(parent, acquirer));
        when(transactionRepository.findByUser_IdOrderByTradeDateAscCreatedAtAsc(owner.getId())).thenReturn(List.of(
                txn(parent, InvestmentTransactionType.buy, "10", "100", LocalDate.of(2020, 1, 1))));
        when(corporateActionRepository.findByInstrumentIdsWithInstruments(anyCollection())).thenReturn(List.of(merger));
        when(corporateActionRepository.findByTargetInstrumentIdsWithInstruments(anyCollection())).thenReturn(List.of(merger));

        List<InvestmentService.HoldingLots> lots = service.getAllHoldingLots();

        assertEquals(0, lots.get(0).openLots().size(), "the transferor closed on the merger");
        HoldingTrace.OpenLot seeded = lots.get(1).openLots().get(0);
        assertEquals(0, d("20").compareTo(seeded.quantity()));
        assertEquals(0, d("1000").compareTo(seeded.cost()));
        assertEquals(LocalDate.of(2020, 1, 1), seeded.buyDate());
        verify(holdingRepository, never()).findByBrokerAccountIdAndInstrumentId(any(), any());
        verify(corporateActionRepository, never()).findByInstrumentIdOrderByExDateAsc(any());
        verify(corporateActionRepository, never()).findByTargetInstrumentIdOrderByExDateAsc(any());
        verify(classificationRepository, never()).findByHoldingId(any());
    }
}
