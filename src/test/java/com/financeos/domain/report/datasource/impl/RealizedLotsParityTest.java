package com.financeos.domain.report.datasource.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.financeos.domain.account.Account;
import com.financeos.domain.holding.Holding;
import com.financeos.domain.holding.HoldingRepository;
import com.financeos.domain.instrument.Instrument;
import com.financeos.domain.instrument.InstrumentPriceRepository;
import com.financeos.domain.instrument.InstrumentType;
import com.financeos.domain.instrument.corporateaction.CorporateActionRepository;
import com.financeos.domain.investment.HoldingPosition;
import com.financeos.domain.investment.InvestmentService;
import com.financeos.domain.investment.InvestmentTransaction;
import com.financeos.domain.investment.InvestmentTransactionRepository;
import com.financeos.domain.investment.InvestmentTransactionType;
import com.financeos.domain.investment.dto.RealizedLot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

class RealizedLotsParityTest {

    private HoldingRepository holdingRepository;
    private InvestmentTransactionRepository transactionRepository;
    private CorporateActionRepository corporateActionRepository;
    private InstrumentPriceRepository priceRepository;
    private InvestmentService investmentService;
    private RealizedLotsDatasource datasource;

    private Holding holding;

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

        datasource = new RealizedLotsDatasource(investmentService);

        Account account = new Account();
        account.setId(UUID.randomUUID());
        account.setName("Zerodha");

        Instrument instrument = new Instrument();
        instrument.setId(UUID.randomUUID());
        instrument.setName("TATA MOTORS");
        instrument.setType(InstrumentType.stock);

        holding = new Holding();
        holding.setId(UUID.randomUUID());
        holding.setBrokerAccount(account);
        holding.setInstrument(instrument);
    }

    @Test
    void parityContractHoldingRealizedEqualsSumLotRealizedPnl() {
        // Buy 10 @ 100 on Jan 1
        InvestmentTransaction buy1 = new InvestmentTransaction();
        buy1.setHolding(holding);
        buy1.setType(InvestmentTransactionType.buy);
        buy1.setQuantity(new BigDecimal("10"));
        buy1.setPrice(new BigDecimal("100"));
        buy1.setTradeDate(LocalDate.of(2025, 1, 1));

        // Buy 20 @ 150 on Feb 1
        InvestmentTransaction buy2 = new InvestmentTransaction();
        buy2.setHolding(holding);
        buy2.setType(InvestmentTransactionType.buy);
        buy2.setQuantity(new BigDecimal("20"));
        buy2.setPrice(new BigDecimal("150"));
        buy2.setTradeDate(LocalDate.of(2025, 2, 1));

        // Sell 15 @ 200 on Mar 1 (10 matched from buy1 @ 100, 5 matched from buy2 @ 150)
        InvestmentTransaction sell1 = new InvestmentTransaction();
        sell1.setHolding(holding);
        sell1.setType(InvestmentTransactionType.sell);
        sell1.setQuantity(new BigDecimal("15"));
        sell1.setPrice(new BigDecimal("200"));
        sell1.setTradeDate(LocalDate.of(2025, 3, 1));

        when(holdingRepository.findAllWithDetails()).thenReturn(List.of(holding));
        when(transactionRepository.findByHoldingIdOrderByTradeDateAscCreatedAtAsc(holding.getId()))
                .thenReturn(List.of(buy1, buy2, sell1));
        when(corporateActionRepository.findByInstrumentIdOrderByExDateAsc(holding.getInstrument().getId()))
                .thenReturn(List.of());

        HoldingPosition pos = investmentService.calculateHoldingPosition(holding);
        List<RealizedLot> lots = investmentService.getAllRealizedLots();

        assertNotNull(pos);
        assertEquals(2, lots.size());

        BigDecimal sumLotPnl = lots.stream()
                .map(RealizedLot::realizedPnl)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // Assert parity: pos.realized() == sum(lot.realizedPnl)
        assertEquals(0, pos.realized().compareTo(sumLotPnl));
        // 10 * (200 - 100) + 5 * (200 - 150) = 1000 + 250 = 1250
        assertEquals(0, new BigDecimal("1250").compareTo(sumLotPnl));
    }

    @Test
    void parityAndBuyDateSurviveASplit() {
        // Buy 10 @ 100 on 2024-01-01; 1:2 split ex 2024-06-01 (20 shares @ 50, buyDate preserved);
        // sell 10 @ 80 on 2025-01-01 (366 days from buy -> long).
        InvestmentTransaction buy = new InvestmentTransaction();
        buy.setHolding(holding);
        buy.setType(InvestmentTransactionType.buy);
        buy.setQuantity(new BigDecimal("10"));
        buy.setPrice(new BigDecimal("100"));
        buy.setTradeDate(LocalDate.of(2024, 1, 1));

        InvestmentTransaction sell = new InvestmentTransaction();
        sell.setHolding(holding);
        sell.setType(InvestmentTransactionType.sell);
        sell.setQuantity(new BigDecimal("10"));
        sell.setPrice(new BigDecimal("80"));
        sell.setTradeDate(LocalDate.of(2025, 1, 1));

        com.financeos.domain.instrument.corporateaction.CorporateAction split =
                new com.financeos.domain.instrument.corporateaction.CorporateAction();
        split.setId(UUID.randomUUID());
        split.setInstrument(holding.getInstrument());
        split.setType(com.financeos.domain.instrument.corporateaction.CorporateActionType.split);
        split.setRatioFrom(1);
        split.setRatioTo(2);
        split.setExDate(LocalDate.of(2024, 6, 1));

        when(holdingRepository.findAllWithDetails()).thenReturn(List.of(holding));
        when(transactionRepository.findByHoldingIdOrderByTradeDateAscCreatedAtAsc(holding.getId()))
                .thenReturn(List.of(buy, sell));
        when(corporateActionRepository.findByInstrumentIdOrderByExDateAsc(holding.getInstrument().getId()))
                .thenReturn(List.of(split));

        HoldingPosition pos = investmentService.calculateHoldingPosition(holding);
        List<RealizedLot> lots = investmentService.getAllRealizedLots();

        assertEquals(1, lots.size());
        RealizedLot lot = lots.get(0);
        // 10 * (80 - 50) = 300
        assertEquals(0, new BigDecimal("300").compareTo(lot.realizedPnl()));
        // Parity with the holding-level realized figure
        assertEquals(0, pos.realized().compareTo(lot.realizedPnl()));
        // buyDate survives the in-place split adjustment -> long term
        assertEquals(LocalDate.of(2024, 1, 1), lot.buyDate());
        assertEquals("long", lot.term());
    }

    @Test
    void termBoundaryCondition() {
        // Buy 10 @ 100 on Jan 1 2025
        InvestmentTransaction buy = new InvestmentTransaction();
        buy.setHolding(holding);
        buy.setType(InvestmentTransactionType.buy);
        buy.setQuantity(new BigDecimal("10"));
        buy.setPrice(new BigDecimal("100"));
        buy.setTradeDate(LocalDate.of(2025, 1, 1));

        // Sell 5 @ 120 on Jan 1 2026 (365 days -> short)
        InvestmentTransaction sellShort = new InvestmentTransaction();
        sellShort.setHolding(holding);
        sellShort.setType(InvestmentTransactionType.sell);
        sellShort.setQuantity(new BigDecimal("5"));
        sellShort.setPrice(new BigDecimal("120"));
        sellShort.setTradeDate(LocalDate.of(2026, 1, 1));

        // Sell 5 @ 130 on Jan 2 2026 (366 days -> long)
        InvestmentTransaction sellLong = new InvestmentTransaction();
        sellLong.setHolding(holding);
        sellLong.setType(InvestmentTransactionType.sell);
        sellLong.setQuantity(new BigDecimal("5"));
        sellLong.setPrice(new BigDecimal("130"));
        sellLong.setTradeDate(LocalDate.of(2026, 1, 2));

        when(holdingRepository.findAllWithDetails()).thenReturn(List.of(holding));
        when(transactionRepository.findByHoldingIdOrderByTradeDateAscCreatedAtAsc(holding.getId()))
                .thenReturn(List.of(buy, sellShort, sellLong));

        List<RealizedLot> lots = investmentService.getAllRealizedLots();
        assertEquals(2, lots.size());

        assertEquals(365, lots.get(0).holdingDays());
        assertEquals("short", lots.get(0).term());

        assertEquals(366, lots.get(1).holdingDays());
        assertEquals("long", lots.get(1).term());
    }
}
