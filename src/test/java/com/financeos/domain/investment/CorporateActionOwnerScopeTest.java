package com.financeos.domain.investment;

import com.financeos.api.investment.dto.PositionDto;
import com.financeos.core.exception.ValidationException;
import com.financeos.core.security.UserContext;
import com.financeos.core.time.AppTime;
import com.financeos.domain.account.Account;
import com.financeos.domain.holding.Holding;
import com.financeos.domain.holding.HoldingRepository;
import com.financeos.domain.instrument.AssetClassOverrideService;
import com.financeos.domain.instrument.Instrument;
import com.financeos.domain.instrument.InstrumentPrice;
import com.financeos.domain.instrument.InstrumentPriceRepository;
import com.financeos.domain.instrument.InstrumentRepository;
import com.financeos.domain.instrument.InstrumentType;
import com.financeos.domain.instrument.PriceSource;
import com.financeos.domain.instrument.corporateaction.CorporateAction;
import com.financeos.domain.instrument.corporateaction.CorporateActionRepository;
import com.financeos.domain.instrument.corporateaction.CorporateActionService;
import com.financeos.domain.instrument.corporateaction.CorporateActionType;
import com.financeos.domain.investment.dividend.DividendRepository;
import com.financeos.domain.report.datasource.impl.PortfolioValueDatasource;
import com.financeos.domain.user.User;
import com.financeos.domain.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Corporate actions are per user: every engine read names the HOLDING's owner, also outside a request
 * (jobs, chat) where no user is signed in and the Hibernate userFilter is off, so one user's action
 * never reaches another user's lots, day change, cash-in-lieu share, dividends or portfolio value.
 */
class CorporateActionOwnerScopeTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 9);

    private HoldingRepository holdingRepository;
    private InvestmentTransactionRepository transactionRepository;
    private InstrumentPriceRepository priceRepository;
    private CorporateActionRepository corporateActionRepository;
    private InvestmentService service;
    private Account brokerA;
    private Account brokerB;
    private User alice;
    private User bob;
    private Instrument shared;

    @BeforeEach
    void setUp() {
        AppTime.useClock(Clock.fixed(TODAY.atStartOfDay(IST).toInstant(), IST));
        UserContext.clear();
        holdingRepository = mock(HoldingRepository.class);
        transactionRepository = mock(InvestmentTransactionRepository.class);
        priceRepository = mock(InstrumentPriceRepository.class);
        corporateActionRepository = mock(CorporateActionRepository.class);
        service = new InvestmentService(transactionRepository, holdingRepository, mock(com.financeos.domain.account.AccountRepository.class),
                mock(InstrumentRepository.class), priceRepository, mock(UserRepository.class), corporateActionRepository,
                mock(DividendRepository.class), mock(TradeSettlementClassificationRepository.class),
                mock(org.springframework.context.ApplicationEventPublisher.class),
                mock(com.financeos.domain.investment.fno.FnoTradeRepository.class), mock(AssetClassOverrideService.class));
        alice = user();
        bob = user();
        brokerA = broker("A");
        brokerB = broker("B");
        shared = instrument("Shared Co");
    }

    @AfterEach
    void tearDown() {
        AppTime.reset();
        UserContext.clear();
    }

    private static User user() {
        User u = new User();
        u.setId(UUID.randomUUID());
        return u;
    }

    private static Account broker(String name) {
        Account a = new Account();
        a.setId(UUID.randomUUID());
        a.setName(name);
        return a;
    }

    private static Instrument instrument(String name) {
        Instrument i = new Instrument();
        i.setId(UUID.randomUUID());
        i.setName(name);
        i.setType(InstrumentType.stock);
        return i;
    }

    private static Holding holding(User owner, Account broker, Instrument instrument) {
        Holding h = new Holding(broker, instrument, null);
        h.setId(UUID.randomUUID());
        h.setUser(owner);
        return h;
    }

    private static InvestmentTransaction buy(Holding h, String qty, String price, LocalDate date) {
        InvestmentTransaction t = new InvestmentTransaction();
        t.setHolding(h);
        t.setType(InvestmentTransactionType.buy);
        t.setQuantity(new BigDecimal(qty));
        t.setPrice(new BigDecimal(price));
        t.setTradeDate(date);
        return t;
    }

    private static CorporateAction split(User owner, Instrument instrument, LocalDate ex) {
        CorporateAction ca = new CorporateAction();
        ca.setId(UUID.randomUUID());
        ca.setUser(owner);
        ca.setInstrument(instrument);
        ca.setType(CorporateActionType.split);
        ca.setRatioFrom(1);
        ca.setRatioTo(2);
        ca.setExDate(ex);
        return ca;
    }

    @Test
    void aJobComputingSeveralUsersAppliesEachOnesOwnActionsOnly() {
        Holding hers = holding(alice, brokerA, shared);
        Holding his = holding(bob, brokerB, shared);
        when(holdingRepository.findAllWithDetails()).thenReturn(List.of(hers, his));
        when(transactionRepository.findByHoldingIdOrderByTradeDateAscCreatedAtAsc(hers.getId()))
                .thenReturn(List.of(buy(hers, "10", "100", LocalDate.of(2026, 1, 5))));
        when(transactionRepository.findByHoldingIdOrderByTradeDateAscCreatedAtAsc(his.getId()))
                .thenReturn(List.of(buy(his, "10", "100", LocalDate.of(2026, 1, 5))));
        CorporateAction herSplit = split(alice, shared, TODAY.minusDays(1));
        when(corporateActionRepository.findByUser_IdAndInstrument_IdOrderByExDateAsc(alice.getId(), shared.getId()))
                .thenReturn(List.of(herSplit));
        when(priceRepository.findLatestVisible(eq(shared.getId()), any())).thenReturn(List.of(
                new InstrumentPrice(shared, TODAY, new BigDecimal("55"), PriceSource.YAHOO)));
        InstrumentPrice today = new InstrumentPrice(shared, TODAY, new BigDecimal("55"), PriceSource.YAHOO);
        when(priceRepository.findLatestTwoCloses(anyCollection(), any())).thenReturn(List.of(
                new Object[]{shared.getId().toString(), java.sql.Date.valueOf(TODAY), today.getClose()},
                new Object[]{shared.getId().toString(), java.sql.Date.valueOf(TODAY.minusDays(2)), new BigDecimal("100")}));
        when(corporateActionRepository.findOwnedByInstrumentIdsExDateFrom(anyCollection(), anyCollection(), any()))
                .thenReturn(List.of(herSplit));

        List<PositionDto> positions = service.getAllPositions();

        assertEquals(0, new BigDecimal("20").compareTo(positions.get(0).quantity()), "her split");
        assertEquals(0, new BigDecimal("10").compareTo(positions.get(1).quantity()), "not his");
        assertEquals(0, new BigDecimal("100.00").compareTo(positions.get(0).dayChange()), "(55 − 100 ÷ 2) × 20");
        assertEquals(0, new BigDecimal("-450.00").compareTo(positions.get(1).dayChange()), "no rescale from her split");
        verify(corporateActionRepository).findOwnedByInstrumentIdsExDateFrom(
                eq(List.of(alice.getId(), bob.getId())), anyCollection(), eq(TODAY.minusDays(11)));
        verify(corporateActionRepository).findByUser_IdAndInstrument_IdOrderByExDateAsc(bob.getId(), shared.getId());
        verify(corporateActionRepository).findByUser_IdAndTargetInstrument_IdOrderByExDateAsc(bob.getId(), shared.getId());
    }

    @Test
    void cashInLieuIsSharedOnlyAcrossTheOwnersOwnParentHoldings() {
        Instrument parent = instrument("Parent");
        Holding parentHolding = holding(alice, brokerA, parent);
        Holding child = holding(alice, brokerA, shared);
        CorporateAction merger = new CorporateAction();
        merger.setId(UUID.randomUUID());
        merger.setUser(alice);
        merger.setInstrument(parent);
        merger.setTargetInstrument(shared);
        merger.setType(CorporateActionType.merger);
        merger.setRatioFrom(2);
        merger.setRatioTo(3);
        merger.setExDate(LocalDate.of(2026, 6, 1));
        merger.setFractionalCashInLieu(new BigDecimal("30"));
        when(corporateActionRepository.findByUser_IdAndTargetInstrument_IdOrderByExDateAsc(alice.getId(), shared.getId()))
                .thenReturn(List.of(merger));
        when(holdingRepository.findByBrokerAccountIdAndInstrumentId(brokerA.getId(), parent.getId()))
                .thenReturn(Optional.of(parentHolding));
        when(transactionRepository.findByHoldingIdOrderByTradeDateAscCreatedAtAsc(parentHolding.getId()))
                .thenReturn(List.of(buy(parentHolding, "7", "100", LocalDate.of(2025, 1, 1))));
        when(holdingRepository.findByUser_IdAndInstrument_Id(alice.getId(), parent.getId())).thenReturn(List.of(parentHolding));

        HoldingPosition position = service.calculateHoldingPosition(child);

        assertEquals(0, new BigDecimal("10").compareTo(position.openQty()));
        // 0.5 fractional share sold for the whole ₹30 (her own parent holdings are the only ones that
        // count), against its cost 0.5 × 700 ÷ 10.5.
        assertEquals(0, new BigDecimal("-3.3333").compareTo(position.realized()));
        verify(holdingRepository).findByUser_IdAndInstrument_Id(alice.getId(), parent.getId());
    }

    @Test
    void dividendsAndPortfolioValueReadTheHoldingOwnersActions() {
        Holding his = holding(bob, brokerB, shared);
        when(transactionRepository.findByHoldingIdOrderByTradeDateAscCreatedAtAsc(his.getId()))
                .thenReturn(List.of(buy(his, "10", "100", LocalDate.of(2026, 1, 5))));
        when(corporateActionRepository.findByUser_IdAndInstrument_IdOrderByExDateAsc(bob.getId(), shared.getId()))
                .thenReturn(List.of(split(bob, shared, LocalDate.of(2026, 3, 1))));

        assertEquals(0, new BigDecimal("20").compareTo(service.openQtyAsOf(his, LocalDate.of(2026, 4, 1))));

        PortfolioValueDatasource datasource = new PortfolioValueDatasource(holdingRepository, transactionRepository,
                priceRepository, corporateActionRepository, service);
        when(holdingRepository.findAllWithDetails()).thenReturn(List.of(his));
        datasource.rows();
        verify(corporateActionRepository, org.mockito.Mockito.atLeastOnce())
                .findByUser_IdAndInstrument_IdOrderByExDateAsc(bob.getId(), shared.getId());
        verify(corporateActionRepository, never()).findByUser_IdAndInstrument_IdOrderByExDateAsc(eq(alice.getId()), any());
    }

    @Test
    void theServiceNeedsASignedInUserToWriteAndListsNothingWithoutOne() {
        CorporateActionService caService = new CorporateActionService(corporateActionRepository, mock(InstrumentRepository.class),
                holdingRepository, mock(UserRepository.class), mock(AssetClassOverrideService.class));

        assertThrows(ValidationException.class, () -> caService.createCorporateAction(shared.getId(),
                new com.financeos.api.instrument.dto.CreateCorporateActionRequest(CorporateActionType.split, 1, 2,
                        TODAY, null, null, null, null)));
        assertThrows(ValidationException.class, () -> caService.deleteCorporateAction(shared.getId(), UUID.randomUUID()));
        assertTrue(caService.getAllCorporateActions().isEmpty());
        verifyNoInteractions(corporateActionRepository);
    }

    @Test
    void anotherUsersActionIsNotFound() {
        UserContext.setCurrentUserId(bob.getId());
        CorporateActionService caService = new CorporateActionService(corporateActionRepository, mock(InstrumentRepository.class),
                holdingRepository, mock(UserRepository.class), mock(AssetClassOverrideService.class));
        UUID hers = UUID.randomUUID();
        when(corporateActionRepository.findByIdAndUser_Id(hers, bob.getId())).thenReturn(Optional.empty());

        assertThrows(com.financeos.core.exception.ResourceNotFoundException.class,
                () -> caService.deleteCorporateAction(shared.getId(), hers));
        verify(corporateActionRepository, never()).delete(any());
    }
}
