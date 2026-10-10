package com.financeos.domain.investment;

import com.financeos.api.investment.dto.InvestmentTransactionResponse;
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
import com.financeos.domain.instrument.UserInstrumentOverride;
import com.financeos.domain.instrument.corporateaction.CorporateAction;
import com.financeos.domain.instrument.corporateaction.CorporateActionRepository;
import com.financeos.domain.instrument.corporateaction.CorporateActionType;
import com.financeos.domain.investment.dividend.DividendRepository;
import com.financeos.domain.investment.dto.RealizedLot;
import com.financeos.domain.user.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * InvestmentService shows instruments as their reader does (their display overrides on positions,
 * trades, realised lots, the summary and merger names) and prices them with the reader's own
 * MANUAL prices — the holding's owner when no request user is set.
 */
class InvestmentInstrumentOverridesTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 10);

    private HoldingRepository holdingRepository;
    private InvestmentTransactionRepository transactionRepository;
    private InstrumentPriceRepository priceRepository;
    private CorporateActionRepository corporateActionRepository;
    private AssetClassOverrideService overrides;
    private InvestmentService service;
    private User owner;
    private Account broker;

    @BeforeEach
    void setUp() {
        AppTime.useClock(Clock.fixed(TODAY.atStartOfDay(IST).toInstant(), IST));
        holdingRepository = mock(HoldingRepository.class);
        transactionRepository = mock(InvestmentTransactionRepository.class);
        priceRepository = mock(InstrumentPriceRepository.class);
        corporateActionRepository = mock(CorporateActionRepository.class);
        overrides = mock(AssetClassOverrideService.class);
        service = new InvestmentService(transactionRepository, holdingRepository,
                mock(com.financeos.domain.account.AccountRepository.class),
                mock(com.financeos.domain.instrument.InstrumentRepository.class), priceRepository,
                mock(com.financeos.domain.user.UserRepository.class), corporateActionRepository,
                mock(DividendRepository.class), mock(TradeSettlementClassificationRepository.class),
                mock(org.springframework.context.ApplicationEventPublisher.class),
                mock(com.financeos.domain.investment.fno.FnoTradeRepository.class), overrides);
        owner = new User();
        owner.setId(UUID.randomUUID());
        broker = new Account();
        broker.setId(UUID.randomUUID());
        broker.setName("Zerodha");
    }

    @AfterEach
    void tearDown() {
        AppTime.reset();
        UserContext.clear();
    }

    private Holding holding(InstrumentType type, String name) {
        Instrument i = new Instrument();
        i.setId(UUID.randomUUID());
        i.setType(type);
        i.setName(name);
        i.setSymbol("SYM");
        Holding h = new Holding(broker, i, null);
        h.setId(UUID.randomUUID());
        h.setUser(owner);
        return h;
    }

    private InvestmentTransaction txn(Holding h, InvestmentTransactionType type, String qty, String price, LocalDate d) {
        InvestmentTransaction t = new InvestmentTransaction();
        t.setId(UUID.randomUUID());
        t.setHolding(h);
        t.setType(type);
        t.setQuantity(new BigDecimal(qty));
        t.setPrice(new BigDecimal(price));
        t.setTradeDate(d);
        return t;
    }

    private void renamed(Holding h, String name, InstrumentType type) {
        UserInstrumentOverride row = new UserInstrumentOverride(owner.getId(), h.getInstrument().getId());
        row.setName(name);
        row.setType(type);
        when(overrides.overridesFor(owner.getId())).thenReturn(InstrumentOverrides.of(List.of(row)));
    }

    @Test
    void withoutAUserThereAreNoOverridesAndNoQuery() {
        assertSame(InstrumentOverrides.NONE, service.instrumentOverrides());
        verifyNoInteractions(overrides);
    }

    @Test
    void positionsShowTheReadersNameTypeAndPrices() {
        UserContext.setCurrentUserId(owner.getId());
        Holding fund = holding(InstrumentType.mutual_fund, "Catalog Fund");
        renamed(fund, "My Fund", InstrumentType.stock);
        when(holdingRepository.findAllWithDetails()).thenReturn(List.of(fund));
        when(transactionRepository.findByHoldingIdOrderByTradeDateAscCreatedAtAsc(fund.getId())).thenReturn(List.of(
                txn(fund, InvestmentTransactionType.buy, "10", "10", TODAY.minusDays(30))));
        when(priceRepository.findLatestVisible(fund.getInstrument().getId(), owner.getId())).thenReturn(List.of(
                new InstrumentPrice(fund.getInstrument(), TODAY.minusDays(1), new BigDecimal("12"), PriceSource.AMFI),
                InstrumentPrice.manual(fund.getInstrument(), owner.getId(), TODAY.minusDays(1), new BigDecimal("15"))));

        PositionDto p = service.getAllPositions().get(0);

        assertEquals("My Fund", p.instrument().name());
        assertEquals(InstrumentType.stock, p.instrument().type());
        assertEquals(AssetClass.EQUITY, p.assetClass(), "re-derived from the reader's type");
        assertEquals(0, new BigDecimal("150").compareTo(p.currentValue()), "their own manual price");
        verify(priceRepository).findLatestTwoCloses(anyCollection(), eq(owner.getId().toString()));
    }

    @Test
    void outsideARequestTheHoldingsOwnerIsTheViewer() {
        Holding stock = holding(InstrumentType.stock, "Catalog Co");
        when(transactionRepository.findByHoldingIdOrderByTradeDateAscCreatedAtAsc(stock.getId())).thenReturn(List.of(
                txn(stock, InvestmentTransactionType.buy, "2", "10", TODAY.minusDays(30))));
        when(priceRepository.findLatestVisible(stock.getInstrument().getId(), owner.getId())).thenReturn(List.of(
                InstrumentPrice.manual(stock.getInstrument(), owner.getId(), TODAY, new BigDecimal("30"))));

        HoldingPosition position = service.calculateHoldingPosition(stock);

        assertEquals(0, new BigDecimal("60").compareTo(position.currentValue()));
    }

    @Test
    void tradesShowTheReadersName() {
        UserContext.setCurrentUserId(owner.getId());
        Holding stock = holding(InstrumentType.stock, "Catalog Co");
        renamed(stock, "Mine Co", null);
        InvestmentTransaction t = txn(stock, InvestmentTransactionType.buy, "1", "1", TODAY);
        when(transactionRepository.findFilteredTransactions(any(), any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(t), PageRequest.of(0, 50), 1));

        InvestmentTransactionResponse r = service.getTransactions(null, null, null, null, PageRequest.of(0, 50))
                .getContent().get(0);

        assertEquals("Mine Co", r.instrument().name());
        assertEquals(InstrumentType.stock, r.instrument().type());
    }

    @Test
    void realisedLotsShowTheReadersNameAndType() {
        UserContext.setCurrentUserId(owner.getId());
        Holding stock = holding(InstrumentType.stock, "Catalog Co");
        renamed(stock, "Mine Co", InstrumentType.etf);
        when(holdingRepository.findAllWithDetails()).thenReturn(List.of(stock));
        when(transactionRepository.findByHoldingIdOrderByTradeDateAscCreatedAtAsc(stock.getId())).thenReturn(List.of(
                txn(stock, InvestmentTransactionType.buy, "10", "10", TODAY.minusDays(30)),
                txn(stock, InvestmentTransactionType.sell, "4", "12", TODAY.minusDays(5))));

        RealizedLot lot = service.getAllRealizedLots().get(0);

        assertEquals("Mine Co", lot.instrumentName());
        assertEquals(InstrumentType.etf, lot.instrumentType());
    }

    @Test
    void theSummaryGroupsByTheReadersType() {
        UserContext.setCurrentUserId(owner.getId());
        Holding fund = holding(InstrumentType.mutual_fund, "Catalog Fund");
        renamed(fund, null, InstrumentType.etf);
        when(holdingRepository.findAllWithDetails()).thenReturn(List.of(fund));
        when(transactionRepository.findByHoldingIdOrderByTradeDateAscCreatedAtAsc(fund.getId())).thenReturn(List.of(
                txn(fund, InvestmentTransactionType.buy, "1", "10", TODAY.minusDays(30))));

        SummaryResponse summary = service.getSummary();

        assertEquals(List.of(InstrumentType.etf),
                summary.byInstrumentType().stream().map(SummaryResponse.InstrumentTypeSummaryDto::type).toList());
    }

    @Test
    void aMergersTargetIsNamedAsTheReaderNamesIt() {
        Holding from = holding(InstrumentType.stock, "HDFC Ltd");
        Holding target = holding(InstrumentType.stock, "HDFC Bank");
        UserInstrumentOverride row = new UserInstrumentOverride(owner.getId(), target.getInstrument().getId());
        row.setName("My Bank");
        when(overrides.overridesFor(owner.getId())).thenReturn(InstrumentOverrides.of(List.of(row)));
        CorporateAction merger = new CorporateAction();
        merger.setId(UUID.randomUUID());
        merger.setInstrument(from.getInstrument());
        merger.setTargetInstrument(target.getInstrument());
        merger.setType(CorporateActionType.merger);
        merger.setRatioFrom(1);
        merger.setRatioTo(1);
        merger.setCostAllocationPct(new BigDecimal("100"));
        merger.setExDate(TODAY.minusDays(10));
        when(transactionRepository.findByHoldingIdOrderByTradeDateAscCreatedAtAsc(from.getId())).thenReturn(List.of(
                txn(from, InvestmentTransactionType.buy, "1", "10", TODAY.minusDays(30))));
        when(corporateActionRepository.findByUser_IdAndInstrument_IdOrderByExDateAsc(owner.getId(), from.getInstrument().getId()))
                .thenReturn(List.of(merger));

        assertEquals("My Bank", service.calculateHoldingPosition(from).mergedIntoName());
    }
}
