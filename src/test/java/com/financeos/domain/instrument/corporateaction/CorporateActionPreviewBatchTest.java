package com.financeos.domain.instrument.corporateaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.financeos.api.instrument.dto.CorporateActionPreviewResponse;
import com.financeos.api.instrument.dto.CorporateActionPreviewResponse.HoldingEffect;
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
import com.financeos.domain.investment.HoldingPosition;
import com.financeos.domain.investment.InvestmentService;
import com.financeos.domain.investment.InvestmentTransaction;
import com.financeos.domain.investment.InvestmentTransactionRepository;
import com.financeos.domain.investment.InvestmentTransactionType;
import com.financeos.domain.investment.TradeSettlementClassificationRepository;
import com.financeos.domain.investment.dividend.DividendRepository;
import com.financeos.domain.user.User;
import java.lang.reflect.Method;
import java.math.BigDecimal;
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
 * The preview reads the engine inputs in a fixed number of batch queries (never per holding) and returns
 * what the per-holding engine reads give, for a demerger whose child holdings appear only with the action.
 */
class CorporateActionPreviewBatchTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 9);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private HoldingRepository holdingRepository;
    private InvestmentTransactionRepository transactionRepository;
    private CorporateActionRepository corporateActionRepository;
    private TradeSettlementClassificationRepository classificationRepository;
    private CorporateActionService corporateActionService;
    private InvestmentService service;
    private CorporateActionPreviewService preview;
    private User owner;
    private Instrument parent;
    private Instrument child;
    private final List<Holding> holdings = new ArrayList<>();
    private final List<InvestmentTransaction> txns = new ArrayList<>();
    private final List<CorporateAction> actions = new ArrayList<>();
    private List<Account> brokers;
    private int previewRatioFrom = 2;
    private BigDecimal previewCashInLieu;

    @BeforeEach
    void setUp() {
        AppTime.useClock(Clock.fixed(TODAY.atStartOfDay(IST).toInstant(), IST));
        holdingRepository = mock(HoldingRepository.class);
        transactionRepository = mock(InvestmentTransactionRepository.class);
        corporateActionRepository = mock(CorporateActionRepository.class);
        classificationRepository = mock(TradeSettlementClassificationRepository.class);
        corporateActionService = mock(CorporateActionService.class);
        service = new InvestmentService(transactionRepository, holdingRepository,
                mock(com.financeos.domain.account.AccountRepository.class), mock(InstrumentRepository.class),
                mock(InstrumentPriceRepository.class), mock(com.financeos.domain.user.UserRepository.class),
                corporateActionRepository, mock(DividendRepository.class), classificationRepository,
                mock(org.springframework.context.ApplicationEventPublisher.class),
                mock(com.financeos.domain.investment.fno.FnoTradeRepository.class), mock(AssetClassOverrideService.class));
        preview = new CorporateActionPreviewService(corporateActionService, service, holdingRepository,
                mock(InstrumentRepository.class), corporateActionRepository);
        owner = new User();
        owner.setId(UUID.randomUUID());
        UserContext.setCurrentUserId(owner.getId());
        parent = instrument("PARENTCO");
        child = instrument("CHILDCO");
    }

    @AfterEach
    void tearDown() {
        AppTime.reset();
        UserContext.clear();
    }

    private Instrument instrument(String name) {
        Instrument i = new Instrument();
        i.setId(UUID.randomUUID());
        i.setType(InstrumentType.stock);
        i.setName(name);
        return i;
    }

    private Holding addHolding(Account broker, Instrument instrument, String qty, String price) {
        Holding h = new Holding(broker, instrument, null);
        h.setId(UUID.randomUUID());
        h.setUser(owner);
        holdings.add(h);
        InvestmentTransaction t = new InvestmentTransaction();
        t.setId(UUID.randomUUID());
        t.setHolding(h);
        t.setType(InvestmentTransactionType.buy);
        t.setQuantity(new BigDecimal(qty));
        t.setPrice(new BigDecimal(price));
        t.setTradeDate(LocalDate.of(2024, 1, 1));
        txns.add(t);
        return h;
    }

    /** {@code n} brokers; each holds the parent, every second one also holds the child; wires the repositories. */
    private void scenario(int n) {
        brokers = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Account a = new Account();
            a.setId(UUID.randomUUID());
            a.setName("Broker " + i);
            brokers.add(a);
            addHolding(a, parent, String.valueOf(10 + i), "100");
            if (i % 2 == 0) {
                addHolding(a, child, "5", "50");
            }
        }
        // batch reads
        when(holdingRepository.findByUser_IdAndInstrument_Id(eq(owner.getId()), any())).thenAnswer(inv -> holdings.stream()
                .filter(h -> h.getInstrument().getId().equals(inv.<UUID>getArgument(1))).toList());
        when(holdingRepository.findByUser_IdAndInstrument_IdIn(eq(owner.getId()), anyCollection())).thenAnswer(inv -> {
            java.util.Collection<UUID> ids = inv.getArgument(1);
            return holdings.stream().filter(h -> ids.contains(h.getInstrument().getId())).toList();
        });
        when(transactionRepository.findByUser_IdOrderByTradeDateAscCreatedAtAsc(owner.getId())).thenAnswer(inv -> List.copyOf(txns));
        when(corporateActionRepository.findOwnedByInstrumentIds(eq(owner.getId()), anyCollection())).thenAnswer(inv -> {
            java.util.Collection<UUID> ids = inv.getArgument(1);
            return actions.stream().filter(a -> ids.contains(a.getInstrument().getId())).toList();
        });
        when(corporateActionRepository.findOwnedByTargetInstrumentIds(eq(owner.getId()), anyCollection())).thenAnswer(inv -> {
            java.util.Collection<UUID> ids = inv.getArgument(1);
            return actions.stream().filter(a -> a.getTargetInstrument() != null && ids.contains(a.getTargetInstrument().getId())).toList();
        });
        // the same data through the per-holding reads (what the previous code used)
        when(transactionRepository.findByHoldingIdOrderByTradeDateAscCreatedAtAsc(any())).thenAnswer(inv -> txns.stream()
                .filter(t -> t.getHolding().getId().equals(inv.<UUID>getArgument(0))).toList());
        when(corporateActionRepository.findByUser_IdAndInstrument_IdOrderByExDateAsc(eq(owner.getId()), any())).thenAnswer(inv -> actions.stream()
                .filter(a -> a.getInstrument().getId().equals(inv.<UUID>getArgument(1))).toList());
        when(corporateActionRepository.findByUser_IdAndTargetInstrument_IdOrderByExDateAsc(eq(owner.getId()), any())).thenAnswer(inv -> actions.stream()
                .filter(a -> a.getTargetInstrument() != null && a.getTargetInstrument().getId().equals(inv.<UUID>getArgument(1))).toList());
        when(holdingRepository.findByBrokerAccountIdAndInstrumentId(any(), any())).thenAnswer(inv -> holdings.stream()
                .filter(h -> h.getBrokerAccount().getId().equals(inv.<UUID>getArgument(0))
                        && h.getInstrument().getId().equals(inv.<UUID>getArgument(1))).findFirst());
        // the preview's own create: the demerger plus a child holding at each broker that has none (as the real create does)
        when(corporateActionService.createForPreview(any(), any())).thenAnswer(inv -> {
            CorporateAction demerger = new CorporateAction();
            demerger.setId(UUID.randomUUID());
            demerger.setUser(owner);
            demerger.setInstrument(parent);
            demerger.setTargetInstrument(child);
            demerger.setType(CorporateActionType.demerger);
            demerger.setRatioFrom(previewRatioFrom);
            demerger.setRatioTo(1);
            demerger.setFractionalCashInLieu(previewCashInLieu);
            demerger.setCostAllocationPct(new BigDecimal("20.0"));
            demerger.setExDate(LocalDate.of(2024, 6, 1));
            actions.add(demerger);
            for (Account b : brokers) {
                if (holdings.stream().noneMatch(h -> h.getBrokerAccount() == b && h.getInstrument() == child)) {
                    Holding h = new Holding(b, child, null);
                    h.setId(UUID.randomUUID());
                    h.setUser(owner);
                    holdings.add(h);
                }
            }
            return null;
        });
    }

    private CorporateActionPreviewResponse run(UUID instrumentId, UUID childId) throws Exception {
        Method m = CorporateActionPreviewService.class.getDeclaredMethod("previewRows", UUID.class, UUID.class,
                com.financeos.api.instrument.dto.CreateCorporateActionRequest.class, UUID.class, boolean.class);
        m.setAccessible(true);
        return (CorporateActionPreviewResponse) m.invoke(preview, owner.getId(), instrumentId, null, childId, childId != null);
    }

    private HoldingPosition childAt(Account broker) {
        return holdings.stream()
                .filter(h -> h.getBrokerAccount() == broker && h.getInstrument() == child).findFirst()
                .map(service::calculateHoldingPosition).orElse(null);
    }

    private static BigDecimal q(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    @Test
    void demergerPreviewEqualsThePerHoldingEngine() throws Exception {
        scenario(3);
        assertPreviewEqualsPerHoldingEngine();
    }

    /** 3:1 leaves fractions at two of three brokers; the cash in lieu is shared by each broker's fraction. */
    @Test
    void demergerWithCashInLieuAcrossBrokersEqualsThePerHoldingEngine() throws Exception {
        scenario(3);
        previewRatioFrom = 3;
        previewCashInLieu = new BigDecimal("90");
        CorporateActionPreviewResponse response = assertPreviewEqualsPerHoldingEngine();
        assertEquals(true, response.holdings().get(0).fractionalRealized().signum() != 0);
        assertEquals(true, response.holdings().get(1).fractionalRealized().signum() != 0);
        assertEquals(0, BigDecimal.ZERO.compareTo(response.holdings().get(2).fractionalRealized()));
    }

    /** The batched seed derivation gives each holding what the single-holding one gives (demerger and merger seeds). */
    @Test
    void seedLotsByHoldingEqualSeedLotsForPerHolding() {
        scenario(3);
        Instrument other = instrument("OTHERCO");
        for (int i = 0; i < 3; i++) {
            addHolding(brokers.get(i), other, "20", "60");
        }
        actions.add(seedAction(parent, CorporateActionType.demerger, 3, 1, "25.0", LocalDate.of(2024, 6, 1)));
        actions.add(seedAction(other, CorporateActionType.merger, 7, 2, null, LocalDate.of(2024, 9, 1)));
        List<Holding> all = new ArrayList<>(holdings);

        java.util.Map<UUID, List<InvestmentService.SeedLot>> batch = service.seedLotsByHolding(all);

        int withSeeds = 0;
        for (Holding h : all) {
            List<InvestmentService.SeedLot> single = service.seedLotsFor(h);
            assertEquals(single, batch.get(h.getId()));
            if (!single.isEmpty()) {
                withSeeds++;
            }
        }
        assertEquals(true, withSeeds >= 2);
    }

    private CorporateAction seedAction(Instrument from, CorporateActionType type, int ratioFrom, int ratioTo,
                                       String allocationPct, LocalDate exDate) {
        CorporateAction ca = new CorporateAction();
        ca.setId(UUID.randomUUID());
        ca.setUser(owner);
        ca.setInstrument(from);
        ca.setTargetInstrument(child);
        ca.setType(type);
        ca.setRatioFrom(ratioFrom);
        ca.setRatioTo(ratioTo);
        ca.setFractionalCashInLieu(new BigDecimal("50"));
        if (allocationPct != null) {
            ca.setCostAllocationPct(new BigDecimal(allocationPct));
        }
        ca.setExDate(exDate);
        return ca;
    }

    /** The child is also the target of another instrument's earlier demerger: its seed lots need that parent too. */
    @Test
    void childSeededByAnotherInstrumentsActionEqualsThePerHoldingEngine() throws Exception {
        scenario(3);
        Instrument other = instrument("OTHERCO");
        for (int i = 0; i < 2; i++) {
            addHolding(brokers.get(i), other, "40", "80");
        }
        CorporateAction earlier = new CorporateAction();
        earlier.setId(UUID.randomUUID());
        earlier.setUser(owner);
        earlier.setInstrument(other);
        earlier.setTargetInstrument(child);
        earlier.setType(CorporateActionType.demerger);
        earlier.setRatioFrom(1);
        earlier.setRatioTo(1);
        earlier.setCostAllocationPct(new BigDecimal("30.0"));
        earlier.setExDate(LocalDate.of(2024, 3, 1));
        actions.add(earlier);
        // sanity: the other parent really adds to the child at broker 0 in the per-holding engine
        assertEquals(0, new BigDecimal("45").compareTo(childAt(brokers.get(0)).openQty()));

        clearInvocations(holdingRepository);
        assertPreviewEqualsPerHoldingEngine();
        // the other parents are one query per pass, not one per holding
        verify(holdingRepository, times(2)).findByUser_IdAndInstrument_IdIn(eq(owner.getId()), anyCollection());
    }

    private CorporateActionPreviewResponse assertPreviewEqualsPerHoldingEngine() throws Exception {
        // expected "before" through the per-holding engine reads
        List<HoldingPosition> parentBefore = new ArrayList<>();
        List<HoldingPosition> childBefore = new ArrayList<>();
        List<BigDecimal> fracBefore = new ArrayList<>();
        List<Holding> parents = new ArrayList<>(holdings.stream().filter(h -> h.getInstrument() == parent).toList());
        for (Holding p : parents) {
            parentBefore.add(service.calculateHoldingPosition(p));
            HoldingPosition c = childAt(p.getBrokerAccount());
            childBefore.add(c);
            fracBefore.add(c == null ? null : service.seedFractionalRealized(c.holding()));
        }

        CorporateActionPreviewResponse response = run(parent.getId(), child.getId());

        assertEquals(3, response.holdings().size());
        for (int i = 0; i < parents.size(); i++) {
            Holding p = parents.get(i);
            HoldingEffect row = response.holdings().get(i);
            HoldingPosition after = service.calculateHoldingPosition(p);
            HoldingPosition childAfter = childAt(p.getBrokerAccount());
            assertEquals(p.getBrokerAccount().getId(), row.brokerAccountId());
            assertEquals(0, parentBefore.get(i).openQty().compareTo(row.quantityBefore()));
            assertEquals(0, after.openQty().compareTo(row.quantityAfter()));
            assertEquals(0, parentBefore.get(i).openCost().compareTo(row.openCostBefore()));
            assertEquals(0, after.openCost().compareTo(row.openCostAfter()));
            assertNotNull(childAfter);
            BigDecimal childQtyBefore = childBefore.get(i) == null ? null : childBefore.get(i).openQty();
            BigDecimal childCostBefore = childBefore.get(i) == null ? null : childBefore.get(i).openCost();
            assertEquals(0, childAfter.openQty().subtract(q(childQtyBefore)).compareTo(row.childQuantity()));
            assertEquals(0, childAfter.openCost().subtract(q(childCostBefore)).compareTo(row.childCost()));
            assertEquals(0, q(service.seedFractionalRealized(childAfter.holding())).subtract(q(fracBefore.get(i)))
                    .compareTo(row.fractionalRealized()));
        }
        // the demerger actually moved quantity: the preview is not a no-op
        assertEquals(true, response.holdings().get(1).childQuantity().signum() > 0);
        assertEquals(true, response.holdings().get(1).openCostAfter().compareTo(response.holdings().get(1).openCostBefore()) < 0);
        return response;
    }

    @Test
    void splitPreviewWithoutChildHasNoChildFigures() throws Exception {
        scenario(2);
        CorporateActionPreviewResponse response = run(parent.getId(), null);
        assertEquals(2, response.holdings().size());
        assertNull(response.holdings().get(0).childInstrumentId());
        assertNull(response.holdings().get(0).childQuantity());
        assertNull(response.holdings().get(0).fractionalRealized());
    }

    private void verifyFixedQueries() {
        // before and after: one user-wide read of each engine input
        verify(transactionRepository, times(2)).findByUser_IdOrderByTradeDateAscCreatedAtAsc(owner.getId());
        verify(classificationRepository, times(2)).findByUser_Id(owner.getId());
        // the parents once, the target's holdings before and after
        verify(holdingRepository, times(1)).findByUser_IdAndInstrument_Id(owner.getId(), parent.getId());
        verify(holdingRepository, times(2)).findByUser_IdAndInstrument_Id(owner.getId(), child.getId());
        verify(transactionRepository, never()).findByHoldingIdOrderByTradeDateAscCreatedAtAsc(any());
        verify(classificationRepository, never()).findByHoldingId(any());
        verify(corporateActionRepository, never()).findByUser_IdAndInstrument_IdOrderByExDateAsc(any(), any());
        verify(corporateActionRepository, never()).findByUser_IdAndTargetInstrument_IdOrderByExDateAsc(any(), any());
        verify(holdingRepository, never()).findByBrokerAccountIdAndInstrumentId(any(), any());
    }

    @Test
    void queryCountDoesNotGrowWithHoldings() throws Exception {
        scenario(3);
        clearInvocations(holdingRepository, transactionRepository, classificationRepository, corporateActionRepository);
        run(parent.getId(), child.getId());
        verifyFixedQueries();
    }

    @Test
    void queryCountIsTheSameWithManyMoreHoldings() throws Exception {
        scenario(9);
        clearInvocations(holdingRepository, transactionRepository, classificationRepository, corporateActionRepository);
        CorporateActionPreviewResponse response = run(parent.getId(), child.getId());
        assertEquals(9, response.holdings().size());
        verifyFixedQueries();
    }
}
