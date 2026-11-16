package com.financeos.domain.investment;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.financeos.domain.account.Account;
import com.financeos.domain.holding.Holding;
import com.financeos.domain.holding.HoldingRepository;
import com.financeos.domain.instrument.Instrument;
import com.financeos.domain.instrument.InstrumentPriceRepository;
import com.financeos.domain.instrument.InstrumentType;
import com.financeos.domain.instrument.corporateaction.CorporateAction;
import com.financeos.domain.instrument.corporateaction.CorporateActionRepository;
import com.financeos.domain.instrument.corporateaction.CorporateActionType;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

class SameDayCorporateActionOrderTest {

    private static final LocalDate EX = LocalDate.of(2024, 6, 1);

    private TradeSettlementClassificationRepository classificationRepository;
    private InvestmentTransactionRepository transactionRepository;
    private CorporateActionRepository corporateActionRepository;
    private HoldingRepository holdingRepository;
    private InstrumentPriceRepository priceRepository;
    private InvestmentService service;

    private Account broker;
    private Instrument parentInst;
    private Instrument childInst;
    private Instrument childInst2;
    private Instrument targetInst;
    private Holding parent;
    private Holding child;
    private Holding child2;
    private Holding target;

    @BeforeEach
    void setUp() {
        transactionRepository = mock(InvestmentTransactionRepository.class);
        holdingRepository = mock(HoldingRepository.class);
        corporateActionRepository = mock(CorporateActionRepository.class);
        priceRepository = mock(InstrumentPriceRepository.class);
        classificationRepository = mock(TradeSettlementClassificationRepository.class);
        service = new InvestmentService(
                transactionRepository, holdingRepository,
                mock(com.financeos.domain.account.AccountRepository.class),
                mock(com.financeos.domain.instrument.InstrumentRepository.class),
                priceRepository,
                mock(com.financeos.domain.user.UserRepository.class),
                corporateActionRepository,
                mock(com.financeos.domain.investment.dividend.DividendRepository.class),
                classificationRepository,
                mock(org.springframework.context.ApplicationEventPublisher.class),
                mock(com.financeos.domain.investment.fno.FnoTradeRepository.class));

        broker = new Account();
        broker.setId(UUID.randomUUID());
        parentInst = instrument("PARENT");
        childInst = instrument("CHILDA");
        childInst2 = instrument("CHILDB");
        targetInst = instrument("TARGET");
        parent = holding(parentInst);
        child = holding(childInst);
        child2 = holding(childInst2);
        target = holding(targetInst);

        InvestmentTransaction buy = new InvestmentTransaction();
        buy.setType(InvestmentTransactionType.buy);
        buy.setQuantity(new BigDecimal("10"));
        buy.setPrice(new BigDecimal("100"));
        buy.setTradeDate(LocalDate.of(2024, 1, 1));
        buy.setHolding(parent);
        when(transactionRepository.findByHoldingIdOrderByTradeDateAscCreatedAtAsc(parent.getId())).thenReturn(List.of(buy));
        when(holdingRepository.findByBrokerAccountIdAndInstrumentId(broker.getId(), parentInst.getId()))
                .thenReturn(Optional.of(parent));
    }

    private Instrument instrument(String symbol) {
        Instrument i = new Instrument();
        i.setId(UUID.randomUUID());
        i.setName(symbol);
        i.setSymbol(symbol);
        i.setType(InstrumentType.stock);
        return i;
    }

    private Holding holding(Instrument i) {
        Holding h = new Holding(broker, i, null);
        h.setId(UUID.randomUUID());
        return h;
    }

    private CorporateAction action(CorporateActionType type, Instrument to, String pct) {
        CorporateAction ca = new CorporateAction();
        ca.setId(UUID.randomUUID());
        ca.setInstrument(parentInst);
        ca.setTargetInstrument(to);
        ca.setType(type);
        ca.setRatioFrom(1);
        ca.setRatioTo(1);
        if (pct != null) {
            ca.setCostAllocationPct(new BigDecimal(pct));
        }
        ca.setExDate(EX);
        return ca;
    }

    private void stub(Instrument inst, List<CorporateAction> asParent, List<CorporateAction> asTarget) {
        when(corporateActionRepository.findByUser_IdAndInstrument_IdOrderByExDateAsc(null, inst.getId()))
                .thenReturn(asParent);
        when(corporateActionRepository.findByUser_IdAndTargetInstrument_IdOrderByExDateAsc(null, inst.getId()))
                .thenReturn(asTarget);
    }

    private BigDecimal[] position(Holding h) throws Exception {
        Method m = InvestmentService.class.getDeclaredMethod("calculateHoldingPosition", Holding.class);
        m.setAccessible(true);
        Object pos = m.invoke(service, h);
        return new BigDecimal[] {
                (BigDecimal) pos.getClass().getMethod("openQty").invoke(pos),
                (BigDecimal) pos.getClass().getMethod("openCost").invoke(pos)};
    }

    private void assertAmount(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual), "expected " + expected + " got " + actual);
    }

    private void demergerAndMerger(boolean mergerFirst) throws Exception {
        CorporateAction demerger = action(CorporateActionType.demerger, childInst, "30");
        CorporateAction merger = action(CorporateActionType.merger, targetInst, null);
        List<CorporateAction> parentActions = mergerFirst ? List.of(merger, demerger) : List.of(demerger, merger);
        stub(parentInst, parentActions, List.of());
        stub(childInst, List.of(), List.of(demerger));
        stub(targetInst, List.of(), List.of(merger));

        BigDecimal[] c = position(child);
        assertAmount("10", c[0]);
        assertAmount("300", c[1]);
        BigDecimal[] t = position(target);
        assertAmount("10", t[0]);
        assertAmount("700", t[1]);
        assertAmount("0", position(parent)[0]);
    }

    @Test
    void demergerThenMergerSameDay_mergerCreatedFirst() throws Exception {
        demergerAndMerger(true);
    }

    @Test
    void demergerThenMergerSameDay_demergerCreatedFirst() throws Exception {
        demergerAndMerger(false);
    }

    @Test
    void twoDemergersSameDay_eachCostIsShareOfOriginal() throws Exception {
        CorporateAction a = action(CorporateActionType.demerger, childInst, "20");
        CorporateAction b = action(CorporateActionType.demerger, childInst2, "30");
        stub(parentInst, List.of(a, b), List.of());
        stub(childInst, List.of(), List.of(a));
        stub(childInst2, List.of(), List.of(b));

        assertAmount("200", position(child)[1]);
        assertAmount("300", position(child2)[1]);
    }

    @Test
    void sameDayRankOrdersKeepAliveBeforeDemergerBeforeMerger() {
        assertTrue(CorporateActionType.split.sameDayRank() < CorporateActionType.demerger.sameDayRank());
        assertTrue(CorporateActionType.bonus.sameDayRank() < CorporateActionType.demerger.sameDayRank());
        assertTrue(CorporateActionType.demerger.sameDayRank() < CorporateActionType.merger.sameDayRank());
        assertEquals(CorporateActionType.merger.sameDayRank(), CorporateActionType.delisted.sameDayRank());
    }
}
