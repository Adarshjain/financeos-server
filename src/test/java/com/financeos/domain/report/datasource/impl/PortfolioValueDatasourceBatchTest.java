package com.financeos.domain.report.datasource.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.financeos.core.security.UserContext;
import com.financeos.core.time.AppTime;
import com.financeos.domain.account.Account;
import com.financeos.domain.holding.Holding;
import com.financeos.domain.holding.HoldingRepository;
import com.financeos.domain.instrument.AssetClassOverrideService;
import com.financeos.domain.instrument.Instrument;
import com.financeos.domain.instrument.InstrumentPriceRepository;
import com.financeos.domain.instrument.InstrumentRepository;
import com.financeos.domain.instrument.InstrumentType;
import com.financeos.domain.instrument.corporateaction.CorporateActionRepository;
import com.financeos.domain.investment.InvestmentService;
import com.financeos.domain.investment.InvestmentTransaction;
import com.financeos.domain.investment.InvestmentTransactionRepository;
import com.financeos.domain.investment.InvestmentTransactionType;
import com.financeos.domain.investment.TradeSettlementClassification;
import com.financeos.domain.investment.TradeSettlementClassificationRepository;
import com.financeos.domain.investment.dividend.DividendRepository;
import com.financeos.domain.user.User;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The portfolio value report reads transactions, corporate actions, classifications and seeds once for all
 * holdings (not per holding, nor per date x holding) and values each holding as the per-holding engine does.
 */
class PortfolioValueDatasourceBatchTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 3, 20);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private HoldingRepository holdingRepository;
    private InvestmentTransactionRepository transactionRepository;
    private CorporateActionRepository corporateActionRepository;
    private TradeSettlementClassificationRepository classificationRepository;
    private InstrumentPriceRepository priceRepository;
    private InvestmentService service;
    private PortfolioValueDatasource datasource;
    private User owner;
    private Account broker;
    private final List<Holding> holdings = new ArrayList<>();
    private final List<InvestmentTransaction> txns = new ArrayList<>();
    private final List<TradeSettlementClassification> classifications = new ArrayList<>();

    @BeforeEach
    void setUp() {
        AppTime.useClock(Clock.fixed(TODAY.atStartOfDay(IST).toInstant(), IST));
        holdingRepository = mock(HoldingRepository.class);
        transactionRepository = mock(InvestmentTransactionRepository.class);
        corporateActionRepository = mock(CorporateActionRepository.class);
        classificationRepository = mock(TradeSettlementClassificationRepository.class);
        priceRepository = mock(InstrumentPriceRepository.class);
        service = new InvestmentService(transactionRepository, holdingRepository,
                mock(com.financeos.domain.account.AccountRepository.class), mock(InstrumentRepository.class),
                priceRepository, mock(com.financeos.domain.user.UserRepository.class), corporateActionRepository,
                mock(DividendRepository.class), classificationRepository,
                mock(org.springframework.context.ApplicationEventPublisher.class),
                mock(com.financeos.domain.investment.fno.FnoTradeRepository.class), mock(AssetClassOverrideService.class));
        datasource = new PortfolioValueDatasource(holdingRepository, transactionRepository, priceRepository,
                corporateActionRepository, service);
        owner = new User();
        owner.setId(UUID.randomUUID());
        UserContext.setCurrentUserId(owner.getId());
        broker = new Account();
        broker.setId(UUID.randomUUID());
        broker.setName("Broker");
    }

    @AfterEach
    void tearDown() {
        AppTime.reset();
        UserContext.clear();
    }

    private void scenario(int n) {
        LocalDate intradayDay = LocalDate.of(2026, 2, 10);
        for (int i = 0; i < n; i++) {
            Instrument instrument = new Instrument();
            instrument.setId(UUID.randomUUID());
            instrument.setType(InstrumentType.stock);
            instrument.setName("STOCK" + i);
            Holding h = new Holding(broker, instrument, null);
            h.setId(UUID.randomUUID());
            h.setUser(owner);
            holdings.add(h);
            txns.add(txn(h, InvestmentTransactionType.buy, 10 + i, 100 + i, LocalDate.of(2026, 1, 5)));
            txns.add(txn(h, InvestmentTransactionType.buy, 5, 120, LocalDate.of(2026, 2, 1)));
            if (i % 2 == 1) {
                // an intraday-netted day on every second holding
                txns.add(txn(h, InvestmentTransactionType.buy, 4, 130, intradayDay));
                txns.add(txn(h, InvestmentTransactionType.sell, 4, 135, intradayDay));
                classifications.add(new TradeSettlementClassification(owner, broker, h, instrument, intradayDay,
                        new BigDecimal("4"), new BigDecimal("520"), new BigDecimal("540")));
            }
        }
        when(holdingRepository.findAllWithDetailsOfUser(any())).thenReturn(holdings);
        when(transactionRepository.findByHoldingIdInOrderByTradeDateAscCreatedAtAsc(anyCollection())).thenAnswer(inv -> {
            java.util.Collection<UUID> ids = inv.getArgument(0);
            return txns.stream().filter(t -> ids.contains(t.getHolding().getId())).toList();
        });
        when(transactionRepository.findByUser_IdOrderByTradeDateAscCreatedAtAsc(owner.getId())).thenAnswer(inv -> List.copyOf(txns));
        when(classificationRepository.findByHoldingIdIn(anyCollection())).thenAnswer(inv -> {
            java.util.Collection<UUID> ids = inv.getArgument(0);
            return classifications.stream().filter(c -> ids.contains(c.getHolding().getId())).toList();
        });
        when(classificationRepository.findByUser_Id(owner.getId())).thenAnswer(inv -> List.copyOf(classifications));
        // the per-holding reads, used only to compute the expected figures
        when(transactionRepository.findByHoldingIdOrderByTradeDateAscCreatedAtAsc(any())).thenAnswer(inv -> txns.stream()
                .filter(t -> t.getHolding().getId().equals(inv.<UUID>getArgument(0))).toList());
        when(classificationRepository.findByHoldingId(any())).thenAnswer(inv -> classifications.stream()
                .filter(c -> c.getHolding().getId().equals(inv.<UUID>getArgument(0))).toList());
    }

    private static InvestmentTransaction txn(Holding h, InvestmentTransactionType type, int qty, int price, LocalDate date) {
        InvestmentTransaction t = new InvestmentTransaction();
        t.setId(UUID.randomUUID());
        t.setHolding(h);
        t.setType(type);
        t.setQuantity(BigDecimal.valueOf(qty));
        t.setPrice(BigDecimal.valueOf(price));
        t.setTradeDate(date);
        return t;
    }

    @Test
    void valuesEqualThePerHoldingEngineAcrossHoldingsAndDates() {
        scenario(4);
        List<Map<String, Object>> rows = datasource.rows();

        // no stored prices: each holding is valued at its open cost, per month end and today
        List<LocalDate> dates = List.of(LocalDate.of(2026, 1, 31), LocalDate.of(2026, 2, 28), TODAY);
        Map<String, BigDecimal> actual = new LinkedHashMap<>();
        for (Map<String, Object> r : rows) {
            actual.put(r.get("instrument") + "@" + r.get("valueDate"), (BigDecimal) r.get("value"));
        }
        assertEquals(12, rows.size());
        for (Holding h : holdings) {
            for (LocalDate d : dates) {
                BigDecimal cost = BigDecimal.ZERO;
                for (InvestmentService.Lot lot : service.buildOpenLotsBeforeDate(h, d, false, null)) {
                    cost = cost.add(lot.remainingQty.multiply(lot.costPerUnit));
                }
                assertEquals(cost.setScale(2, RoundingMode.HALF_UP), actual.get(h.getInstrument().getName() + "@" + d));
            }
        }
        assertFalse(rows.isEmpty());
    }

    private void verifyFixedQueries() {
        verify(transactionRepository, times(1)).findByHoldingIdInOrderByTradeDateAscCreatedAtAsc(anyCollection());
        // once for the report's own actions, once inside the batched seed derivation
        verify(corporateActionRepository, times(2)).findOwnedByInstrumentIds(eq(owner.getId()), anyCollection());
        verify(classificationRepository, times(1)).findByHoldingIdIn(anyCollection());
        verify(transactionRepository, never()).findByHoldingIdOrderByTradeDateAscCreatedAtAsc(any());
        verify(classificationRepository, never()).findByHoldingId(any());
        verify(corporateActionRepository, never()).findByUser_IdAndInstrument_IdOrderByExDateAsc(any(), any());
        verify(corporateActionRepository, never()).findByUser_IdAndTargetInstrument_IdOrderByExDateAsc(any(), any());
    }

    @Test
    void queryCountDoesNotGrowWithHoldingsOrDates() {
        scenario(3);
        clearInvocations(transactionRepository, corporateActionRepository, classificationRepository);
        datasource.rows();
        verifyFixedQueries();
    }

    @Test
    void queryCountIsTheSameWithManyMoreHoldings() {
        scenario(12);
        clearInvocations(transactionRepository, corporateActionRepository, classificationRepository);
        assertEquals(36, datasource.rows().size());
        verifyFixedQueries();
    }
}
