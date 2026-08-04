package com.financeos.domain.investment;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.financeos.domain.account.Account;
import com.financeos.domain.holding.Holding;
import com.financeos.domain.holding.HoldingRepository;
import com.financeos.domain.holding.HoldingValuationService;
import com.financeos.domain.instrument.Instrument;
import com.financeos.domain.instrument.InstrumentPrice;
import com.financeos.domain.instrument.InstrumentPriceRepository;
import com.financeos.domain.instrument.InstrumentType;
import com.financeos.domain.instrument.corporateaction.CorporateAction;
import com.financeos.domain.instrument.corporateaction.CorporateActionRepository;
import com.financeos.domain.instrument.corporateaction.CorporateActionType;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

class MergerCorporateActionTest {

    private HoldingRepository holdingRepository;
    private InvestmentTransactionRepository transactionRepository;
    private CorporateActionRepository corporateActionRepository;
    private InstrumentPriceRepository priceRepository;
    private InvestmentService investmentService;
    private HoldingValuationService holdingValuationService;

    private Account brokerAccount;
    private Instrument transferorInstrument;
    private Instrument acquirerInstrument;
    private Holding transferorHolding;
    private Holding acquirerHolding;

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
                mock(org.springframework.context.ApplicationEventPublisher.class)
        );

        holdingValuationService = new HoldingValuationService(holdingRepository, investmentService);

        brokerAccount = new Account();
        brokerAccount.setId(UUID.randomUUID());

        transferorInstrument = new Instrument();
        transferorInstrument.setId(UUID.randomUUID());
        transferorInstrument.setName("HDFC Ltd");
        transferorInstrument.setSymbol("HDFC");
        transferorInstrument.setType(InstrumentType.stock);

        acquirerInstrument = new Instrument();
        acquirerInstrument.setId(UUID.randomUUID());
        acquirerInstrument.setName("HDFC Bank");
        acquirerInstrument.setSymbol("HDFCBANK");
        acquirerInstrument.setType(InstrumentType.stock);

        transferorHolding = new Holding(brokerAccount, transferorInstrument, null);
        transferorHolding.setId(UUID.randomUUID());

        acquirerHolding = new Holding(brokerAccount, acquirerInstrument, null);
        acquirerHolding.setId(UUID.randomUUID());
    }

    @Test
    void testTransferorClosedAndAcquirerSeededOnMerger() {
        // Transferor: 100 shares bought @ Rs 100 = Rs 10,000 on 2024-01-01
        InvestmentTransaction buyTxn = new InvestmentTransaction();
        buyTxn.setType(InvestmentTransactionType.buy);
        buyTxn.setQuantity(new BigDecimal("100"));
        buyTxn.setPrice(new BigDecimal("100.00"));
        buyTxn.setTradeDate(LocalDate.of(2024, 1, 1));
        buyTxn.setHolding(transferorHolding);

        // Merger corporate action: HDFC -> HDFC Bank (25 -> 42), ex-date 2024-06-01
        CorporateAction merger = new CorporateAction();
        merger.setId(UUID.randomUUID());
        merger.setInstrument(transferorInstrument);
        merger.setTargetInstrument(acquirerInstrument);
        merger.setType(CorporateActionType.merger);
        merger.setRatioFrom(25);
        merger.setRatioTo(42);
        merger.setCostAllocationPct(new BigDecimal("100.0"));
        merger.setExDate(LocalDate.of(2024, 6, 1));

        // 1. Calculate transferor position
        when(transactionRepository.findByHoldingIdOrderByTradeDateAscCreatedAtAsc(transferorHolding.getId()))
                .thenReturn(List.of(buyTxn));
        when(corporateActionRepository.findByInstrumentIdOrderByExDateAsc(transferorInstrument.getId()))
                .thenReturn(List.of(merger));
        when(corporateActionRepository.findByTargetInstrumentIdOrderByExDateAsc(transferorInstrument.getId()))
                .thenReturn(List.of());
        when(priceRepository.findTopByInstrumentIdOrderByAsOfDesc(transferorInstrument.getId()))
                .thenReturn(Optional.empty());

        HoldingPosition transferorPos = investmentService.calculateHoldingPosition(transferorHolding);

        assertEquals(0, transferorPos.openQty().compareTo(BigDecimal.ZERO));
        assertEquals(0, transferorPos.openCost().compareTo(BigDecimal.ZERO));
        assertEquals(0, transferorPos.realized().compareTo(BigDecimal.ZERO)); // no realized gain/loss
        assertEquals("HDFC Bank", transferorPos.mergedIntoName());
        assertEquals(LocalDate.of(2024, 6, 1), transferorPos.mergedIntoDate());

        // 2. Calculate acquirer position
        when(transactionRepository.findByHoldingIdOrderByTradeDateAscCreatedAtAsc(acquirerHolding.getId()))
                .thenReturn(List.of());
        when(corporateActionRepository.findByInstrumentIdOrderByExDateAsc(acquirerInstrument.getId()))
                .thenReturn(List.of());
        when(corporateActionRepository.findByTargetInstrumentIdOrderByExDateAsc(acquirerInstrument.getId()))
                .thenReturn(List.of(merger));
        when(holdingRepository.findByBrokerAccountIdAndInstrumentId(brokerAccount.getId(), transferorInstrument.getId()))
                .thenReturn(Optional.of(transferorHolding));
        when(priceRepository.findTopByInstrumentIdOrderByAsOfDesc(acquirerInstrument.getId()))
                .thenReturn(Optional.empty());

        HoldingPosition acquirerPos = investmentService.calculateHoldingPosition(acquirerHolding);

        // 100 * (42 / 25) = 168 shares
        assertEquals(0, acquirerPos.openQty().compareTo(new BigDecimal("168.00000000")));
        // 100% cost carried over = 10,000.00
        assertEquals(0, acquirerPos.openCost().compareTo(new BigDecimal("10000.0000")));
    }

    @Test
    void testBrokerMarketValuePostMerger() {
        // Transferor: 100 shares bought @ 100, merged into acquirer
        InvestmentTransaction buyTxn = new InvestmentTransaction();
        buyTxn.setType(InvestmentTransactionType.buy);
        buyTxn.setQuantity(new BigDecimal("100"));
        buyTxn.setPrice(new BigDecimal("100.00"));
        buyTxn.setTradeDate(LocalDate.of(2024, 1, 1));
        buyTxn.setHolding(transferorHolding);

        CorporateAction merger = new CorporateAction();
        merger.setId(UUID.randomUUID());
        merger.setInstrument(transferorInstrument);
        merger.setTargetInstrument(acquirerInstrument);
        merger.setType(CorporateActionType.merger);
        merger.setRatioFrom(25);
        merger.setRatioTo(42);
        merger.setCostAllocationPct(new BigDecimal("100.0"));
        merger.setExDate(LocalDate.of(2024, 6, 1));

        // Transferor latest price (stale)
        InstrumentPrice transferorPrice = new InstrumentPrice();
        transferorPrice.setClose(new BigDecimal("2500.00"));

        // Acquirer latest price (e.g. 1500.00)
        InstrumentPrice acquirerPrice = new InstrumentPrice();
        acquirerPrice.setClose(new BigDecimal("1500.00"));

        // Mocks for transferor calculation
        when(transactionRepository.findByHoldingIdOrderByTradeDateAscCreatedAtAsc(transferorHolding.getId()))
                .thenReturn(List.of(buyTxn));
        when(corporateActionRepository.findByInstrumentIdOrderByExDateAsc(transferorInstrument.getId()))
                .thenReturn(List.of(merger));
        when(corporateActionRepository.findByTargetInstrumentIdOrderByExDateAsc(transferorInstrument.getId()))
                .thenReturn(List.of());
        when(priceRepository.findTopByInstrumentIdOrderByAsOfDesc(transferorInstrument.getId()))
                .thenReturn(Optional.of(transferorPrice));

        // Mocks for acquirer calculation
        when(transactionRepository.findByHoldingIdOrderByTradeDateAscCreatedAtAsc(acquirerHolding.getId()))
                .thenReturn(List.of());
        when(corporateActionRepository.findByInstrumentIdOrderByExDateAsc(acquirerInstrument.getId()))
                .thenReturn(List.of());
        when(corporateActionRepository.findByTargetInstrumentIdOrderByExDateAsc(acquirerInstrument.getId()))
                .thenReturn(List.of(merger));
        when(holdingRepository.findByBrokerAccountIdAndInstrumentId(brokerAccount.getId(), transferorInstrument.getId()))
                .thenReturn(Optional.of(transferorHolding));
        when(priceRepository.findTopByInstrumentIdOrderByAsOfDesc(acquirerInstrument.getId()))
                .thenReturn(Optional.of(acquirerPrice));

        when(holdingRepository.findByBrokerAccountId(brokerAccount.getId()))
                .thenReturn(List.of(transferorHolding, acquirerHolding));

        BigDecimal marketValue = holdingValuationService.getBrokerMarketValue(brokerAccount.getId());

        // Transferor openQty is 0 -> contributes 0
        // Acquirer openQty is 168 @ 1500 = 252,000.00
        assertEquals(0, marketValue.compareTo(new BigDecimal("252000.0000")));
    }

    @Test
    void testCalculateHoldingPositionNoPriceOpenQtyValuesAtCost() {
        InvestmentTransaction buyTxn = new InvestmentTransaction();
        buyTxn.setId(UUID.randomUUID());
        buyTxn.setType(InvestmentTransactionType.buy);
        buyTxn.setQuantity(new BigDecimal("10"));
        buyTxn.setPrice(new BigDecimal("100.00"));
        buyTxn.setTradeDate(LocalDate.of(2024, 1, 1));
        buyTxn.setHolding(transferorHolding);

        when(transactionRepository.findByHoldingIdOrderByTradeDateAscCreatedAtAsc(transferorHolding.getId()))
                .thenReturn(List.of(buyTxn));
        when(corporateActionRepository.findByInstrumentIdOrderByExDateAsc(transferorInstrument.getId()))
                .thenReturn(List.of());
        when(corporateActionRepository.findByTargetInstrumentIdOrderByExDateAsc(transferorInstrument.getId()))
                .thenReturn(List.of());
        when(priceRepository.findTopByInstrumentIdOrderByAsOfDesc(transferorInstrument.getId()))
                .thenReturn(Optional.empty());

        HoldingPosition pos = investmentService.calculateHoldingPosition(transferorHolding);

        assertNull(pos.latestPrice());
        assertEquals(0, pos.currentValue().compareTo(new BigDecimal("1000.0000")));
        assertEquals(0, pos.unrealized().compareTo(new BigDecimal("0.0000")));
        assertEquals(0, pos.unrealizedPercent().compareTo(new BigDecimal("0.00")));
    }

    @Test
    void testGetSummaryIncludesUnpricedHoldingAtCost() {
        InvestmentTransaction buyTxn = new InvestmentTransaction();
        buyTxn.setId(UUID.randomUUID());
        buyTxn.setType(InvestmentTransactionType.buy);
        buyTxn.setQuantity(new BigDecimal("10"));
        buyTxn.setPrice(new BigDecimal("100.00"));
        buyTxn.setTradeDate(LocalDate.of(2024, 1, 1));
        buyTxn.setHolding(transferorHolding);

        when(holdingRepository.findAll())
                .thenReturn(List.of(transferorHolding));
        when(transactionRepository.findByHoldingIdOrderByTradeDateAscCreatedAtAsc(transferorHolding.getId()))
                .thenReturn(List.of(buyTxn));
        when(corporateActionRepository.findByInstrumentIdOrderByExDateAsc(transferorInstrument.getId()))
                .thenReturn(List.of());
        when(corporateActionRepository.findByTargetInstrumentIdOrderByExDateAsc(transferorInstrument.getId()))
                .thenReturn(List.of());
        when(priceRepository.findTopByInstrumentIdOrderByAsOfDesc(transferorInstrument.getId()))
                .thenReturn(Optional.empty());

        com.financeos.api.investment.dto.SummaryResponse summary = investmentService.getSummary();

        assertEquals(0, summary.totalInvested().compareTo(new BigDecimal("1000.0000")));
        assertEquals(0, summary.totalCurrentValue().compareTo(new BigDecimal("1000.0000")));
    }

    @Test
    void testFractionalMergerWithCashInLieu() {
        // 121 A bought @ Rs 100 = Rs 12,100
        InvestmentTransaction buyTxn = new InvestmentTransaction();
        buyTxn.setType(InvestmentTransactionType.buy);
        buyTxn.setQuantity(new BigDecimal("121"));
        buyTxn.setPrice(new BigDecimal("100.00"));
        buyTxn.setTradeDate(LocalDate.of(2024, 1, 1));
        buyTxn.setHolding(transferorHolding);

        // Merger: ratio 100:155 (121 * 155 / 100 = 187.55 entitlement -> 187 whole + 0.55 fraction)
        // cash-in-lieu received = 385
        CorporateAction merger = new CorporateAction();
        merger.setId(UUID.randomUUID());
        merger.setInstrument(transferorInstrument);
        merger.setTargetInstrument(acquirerInstrument);
        merger.setType(CorporateActionType.merger);
        merger.setRatioFrom(100);
        merger.setRatioTo(155);
        merger.setCostAllocationPct(new BigDecimal("100.0"));
        merger.setFractionalCashInLieu(new BigDecimal("385.00"));
        merger.setExDate(LocalDate.of(2024, 6, 1));

        when(transactionRepository.findByHoldingIdOrderByTradeDateAscCreatedAtAsc(acquirerHolding.getId()))
                .thenReturn(List.of());
        when(corporateActionRepository.findByInstrumentIdOrderByExDateAsc(acquirerInstrument.getId()))
                .thenReturn(List.of());
        when(corporateActionRepository.findByTargetInstrumentIdOrderByExDateAsc(acquirerInstrument.getId()))
                .thenReturn(List.of(merger));
        when(holdingRepository.findByBrokerAccountIdAndInstrumentId(brokerAccount.getId(), transferorInstrument.getId()))
                .thenReturn(Optional.of(transferorHolding));
        when(holdingRepository.findByInstrumentId(transferorInstrument.getId()))
                .thenReturn(List.of(transferorHolding));
        when(transactionRepository.findByHoldingIdOrderByTradeDateAscCreatedAtAsc(transferorHolding.getId()))
                .thenReturn(List.of(buyTxn));
        when(corporateActionRepository.findByInstrumentIdOrderByExDateAsc(transferorInstrument.getId()))
                .thenReturn(List.of(merger));
        when(priceRepository.findTopByInstrumentIdOrderByAsOfDesc(acquirerInstrument.getId()))
                .thenReturn(Optional.empty());

        HoldingPosition acquirerPos = investmentService.calculateHoldingPosition(acquirerHolding);

        // 187 whole shares kept
        assertEquals(0, acquirerPos.openQty().compareTo(new BigDecimal("187.00000000")));
        // Cash-in-lieu = 385, fractional cost = 0.55 * (12100 / 187.55) = 35.4839... -> realized PnL = +349.5161
        assertEquals(0, acquirerPos.realized().compareTo(new BigDecimal("349.5161")));
    }

    @Test
    void testFractionalMergerWithZeroCashInLieu() {
        // 121 A bought @ Rs 100 = Rs 12,100
        InvestmentTransaction buyTxn = new InvestmentTransaction();
        buyTxn.setType(InvestmentTransactionType.buy);
        buyTxn.setQuantity(new BigDecimal("121"));
        buyTxn.setPrice(new BigDecimal("100.00"));
        buyTxn.setTradeDate(LocalDate.of(2024, 1, 1));
        buyTxn.setHolding(transferorHolding);

        // Merger: ratio 100:155, fractionalCashInLieu = 0 / null
        CorporateAction merger = new CorporateAction();
        merger.setId(UUID.randomUUID());
        merger.setInstrument(transferorInstrument);
        merger.setTargetInstrument(acquirerInstrument);
        merger.setType(CorporateActionType.merger);
        merger.setRatioFrom(100);
        merger.setRatioTo(155);
        merger.setCostAllocationPct(new BigDecimal("100.0"));
        merger.setFractionalCashInLieu(BigDecimal.ZERO);
        merger.setExDate(LocalDate.of(2024, 6, 1));

        when(transactionRepository.findByHoldingIdOrderByTradeDateAscCreatedAtAsc(acquirerHolding.getId()))
                .thenReturn(List.of());
        when(corporateActionRepository.findByInstrumentIdOrderByExDateAsc(acquirerInstrument.getId()))
                .thenReturn(List.of());
        when(corporateActionRepository.findByTargetInstrumentIdOrderByExDateAsc(acquirerInstrument.getId()))
                .thenReturn(List.of(merger));
        when(holdingRepository.findByBrokerAccountIdAndInstrumentId(brokerAccount.getId(), transferorInstrument.getId()))
                .thenReturn(Optional.of(transferorHolding));
        when(holdingRepository.findByInstrumentId(transferorInstrument.getId()))
                .thenReturn(List.of(transferorHolding));
        when(transactionRepository.findByHoldingIdOrderByTradeDateAscCreatedAtAsc(transferorHolding.getId()))
                .thenReturn(List.of(buyTxn));
        when(corporateActionRepository.findByInstrumentIdOrderByExDateAsc(transferorInstrument.getId()))
                .thenReturn(List.of(merger));
        when(priceRepository.findTopByInstrumentIdOrderByAsOfDesc(acquirerInstrument.getId()))
                .thenReturn(Optional.empty());

        HoldingPosition acquirerPos = investmentService.calculateHoldingPosition(acquirerHolding);

        // 187 whole shares kept
        assertEquals(0, acquirerPos.openQty().compareTo(new BigDecimal("187.00000000")));
        // Cash-in-lieu = 0 -> realized PnL = 0 - 35.4839 = -35.4839
        assertEquals(0, acquirerPos.realized().compareTo(new BigDecimal("-35.4839")));
    }
}
