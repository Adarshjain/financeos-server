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

class DemergerCorporateActionTest {

    private HoldingRepository holdingRepository;
    private InvestmentTransactionRepository transactionRepository;
    private CorporateActionRepository corporateActionRepository;
    private InstrumentPriceRepository priceRepository;
    private InvestmentService investmentService;

    private Account brokerAccount;
    private Instrument parentInstrument;
    private Instrument childInstrument;
    private Holding parentHolding;
    private Holding childHolding;

    @BeforeEach
    void setUp() {
        holdingRepository = mock(HoldingRepository.class);
        transactionRepository = mock(InvestmentTransactionRepository.class);
        corporateActionRepository = mock(CorporateActionRepository.class);
        priceRepository = mock(InstrumentPriceRepository.class);

        investmentService = new InvestmentService(
                transactionRepository,
                holdingRepository,
                mock(com.financeos.domain.account.AccountRepository.class),
                mock(com.financeos.domain.instrument.InstrumentRepository.class),
                priceRepository,
                mock(com.financeos.domain.user.UserRepository.class),
                corporateActionRepository,
                mock(com.financeos.domain.investment.dividend.DividendRepository.class),
                mock(com.financeos.domain.investment.TradeSettlementClassificationRepository.class),
                mock(org.springframework.context.ApplicationEventPublisher.class),
                mock(com.financeos.domain.investment.fno.FnoTradeRepository.class)
        );

        brokerAccount = new Account();
        brokerAccount.setId(UUID.randomUUID());

        parentInstrument = new Instrument();
        parentInstrument.setId(UUID.randomUUID());
        parentInstrument.setName("Parent Corp");
        parentInstrument.setSymbol("PARENT");
        parentInstrument.setType(InstrumentType.stock);

        childInstrument = new Instrument();
        childInstrument.setId(UUID.randomUUID());
        childInstrument.setName("Child SpinCo");
        childInstrument.setSymbol("CHILD");
        childInstrument.setType(InstrumentType.stock);

        parentHolding = new Holding(brokerAccount, parentInstrument, null);
        parentHolding.setId(UUID.randomUUID());

        childHolding = new Holding(brokerAccount, childInstrument, null);
        childHolding.setId(UUID.randomUUID());
    }

    @Test
    void testParentPositionCostReductionOnDemerger() throws Exception {
        // Parent: 100 shares bought @ Rs. 100 = Rs. 10,000
        InvestmentTransaction buyTxn = new InvestmentTransaction();
        buyTxn.setType(InvestmentTransactionType.buy);
        buyTxn.setQuantity(new BigDecimal("100"));
        buyTxn.setPrice(new BigDecimal("100.00"));
        buyTxn.setTradeDate(LocalDate.of(2024, 1, 1));
        buyTxn.setHolding(parentHolding);

        // Demerger on 2024-06-01 with 20% cost allocation, 2:1 ratio (ratioFrom=2, ratioTo=1)
        CorporateAction demerger = new CorporateAction();
        demerger.setId(UUID.randomUUID());
        demerger.setInstrument(parentInstrument);
        demerger.setTargetInstrument(childInstrument);
        demerger.setType(CorporateActionType.demerger);
        demerger.setRatioFrom(2);
        demerger.setRatioTo(1);
        demerger.setCostAllocationPct(new BigDecimal("20.0"));
        demerger.setExDate(LocalDate.of(2024, 6, 1));

        when(transactionRepository.findByHoldingIdOrderByTradeDateAscCreatedAtAsc(parentHolding.getId()))
                .thenReturn(List.of(buyTxn));
        when(corporateActionRepository.findByInstrumentIdOrderByExDateAsc(parentInstrument.getId()))
                .thenReturn(List.of(demerger));
        when(corporateActionRepository.findByTargetInstrumentIdOrderByExDateAsc(parentInstrument.getId()))
                .thenReturn(List.of());
        when(priceRepository.findTopByInstrumentIdOrderByAsOfDesc(parentInstrument.getId()))
                .thenReturn(Optional.empty());

        Method calcMethod = InvestmentService.class.getDeclaredMethod("calculateHoldingPosition", Holding.class);
        calcMethod.setAccessible(true);
        Object posObj = calcMethod.invoke(investmentService, parentHolding);

        // Parent openQty remains 100
        BigDecimal openQty = (BigDecimal) posObj.getClass().getMethod("openQty").invoke(posObj);
        assertEquals(0, openQty.compareTo(new BigDecimal("100")));

        // Parent avgCost is reduced by 20%: 100 * (1 - 0.20) = 80.00
        BigDecimal avgCost = (BigDecimal) posObj.getClass().getMethod("avgCost").invoke(posObj);
        assertEquals(0, avgCost.compareTo(new BigDecimal("80.00")));

        // Parent openCost becomes 8000.00
        BigDecimal openCost = (BigDecimal) posObj.getClass().getMethod("openCost").invoke(posObj);
        assertEquals(0, openCost.compareTo(new BigDecimal("8000.00")));
    }

    @Test
    void testChildHoldingSeedLotsFromDemerger() throws Exception {
        // Parent: 100 shares bought @ Rs. 100 = Rs. 10,000 on 2024-01-01
        InvestmentTransaction parentBuy = new InvestmentTransaction();
        parentBuy.setType(InvestmentTransactionType.buy);
        parentBuy.setQuantity(new BigDecimal("100"));
        parentBuy.setPrice(new BigDecimal("100.00"));
        parentBuy.setTradeDate(LocalDate.of(2024, 1, 1));
        parentBuy.setHolding(parentHolding);

        // Demerger on 2024-06-01: ratio 2:1 (1 child per 2 parent shares), cost allocation 20%
        CorporateAction demerger = new CorporateAction();
        demerger.setId(UUID.randomUUID());
        demerger.setInstrument(parentInstrument);
        demerger.setTargetInstrument(childInstrument);
        demerger.setType(CorporateActionType.demerger);
        demerger.setRatioFrom(2);
        demerger.setRatioTo(1);
        demerger.setCostAllocationPct(new BigDecimal("20.0"));
        demerger.setExDate(LocalDate.of(2024, 6, 1));

        when(transactionRepository.findByHoldingIdOrderByTradeDateAscCreatedAtAsc(childHolding.getId()))
                .thenReturn(List.of());
        when(corporateActionRepository.findByInstrumentIdOrderByExDateAsc(childInstrument.getId()))
                .thenReturn(List.of());
        when(corporateActionRepository.findByTargetInstrumentIdOrderByExDateAsc(childInstrument.getId()))
                .thenReturn(List.of(demerger));
        when(holdingRepository.findByBrokerAccountIdAndInstrumentId(brokerAccount.getId(), parentInstrument.getId()))
                .thenReturn(Optional.of(parentHolding));
        when(transactionRepository.findByHoldingIdOrderByTradeDateAscCreatedAtAsc(parentHolding.getId()))
                .thenReturn(List.of(parentBuy));
        when(corporateActionRepository.findByInstrumentIdOrderByExDateAsc(parentInstrument.getId()))
                .thenReturn(List.of(demerger));
        when(priceRepository.findTopByInstrumentIdOrderByAsOfDesc(childInstrument.getId()))
                .thenReturn(Optional.empty());

        Method calcMethod = InvestmentService.class.getDeclaredMethod("calculateHoldingPosition", Holding.class);
        calcMethod.setAccessible(true);
        Object posObj = calcMethod.invoke(investmentService, childHolding);

        // Child openQty = 100 * (1/2) = 50
        BigDecimal openQty = (BigDecimal) posObj.getClass().getMethod("openQty").invoke(posObj);
        assertEquals(0, openQty.compareTo(new BigDecimal("50.00000000")));

        // Child openCost = 10,000 * 20% = 2000.00
        BigDecimal openCost = (BigDecimal) posObj.getClass().getMethod("openCost").invoke(posObj);
        assertEquals(0, openCost.compareTo(new BigDecimal("2000.00")));

        // Child avgCost = 2000 / 50 = 40.00
        BigDecimal avgCost = (BigDecimal) posObj.getClass().getMethod("avgCost").invoke(posObj);
        assertEquals(0, avgCost.compareTo(new BigDecimal("40.00")));
    }
}
