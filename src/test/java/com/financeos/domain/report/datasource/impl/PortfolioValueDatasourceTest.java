package com.financeos.domain.report.datasource.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.financeos.domain.account.Account;
import com.financeos.domain.holding.Holding;
import com.financeos.domain.holding.HoldingRepository;
import com.financeos.domain.instrument.Instrument;
import com.financeos.domain.instrument.InstrumentPrice;
import com.financeos.domain.instrument.InstrumentPriceRepository;
import com.financeos.domain.instrument.InstrumentType;
import com.financeos.domain.investment.InvestmentService;
import com.financeos.domain.investment.InvestmentService.Lot;
import com.financeos.domain.investment.InvestmentTransaction;
import com.financeos.domain.investment.InvestmentTransactionRepository;
import com.financeos.domain.investment.InvestmentTransactionType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

class PortfolioValueDatasourceTest {

    private HoldingRepository holdingRepository;
    private InvestmentTransactionRepository transactionRepository;
    private InstrumentPriceRepository priceRepository;
    private InvestmentService investmentService;
    private PortfolioValueDatasource datasource;

    private Holding holding;
    private Instrument instrument;

    @BeforeEach
    void setUp() {
        holdingRepository = mock(HoldingRepository.class);
        transactionRepository = mock(InvestmentTransactionRepository.class);
        priceRepository = mock(InstrumentPriceRepository.class);
        investmentService = mock(InvestmentService.class);

        datasource = new PortfolioValueDatasource(
                holdingRepository,
                transactionRepository,
                priceRepository,
                mock(com.financeos.domain.instrument.corporateaction.CorporateActionRepository.class),
                investmentService
        );

        Account account = new Account();
        account.setId(UUID.randomUUID());
        account.setName("Zerodha");

        instrument = new Instrument();
        instrument.setId(UUID.randomUUID());
        instrument.setName("RELIANCE");
        instrument.setType(InstrumentType.stock);

        holding = new Holding();
        holding.setId(UUID.randomUUID());
        holding.setBrokerAccount(account);
        holding.setInstrument(instrument);
    }

    @Test
    void catalogShapeAndName() {
        assertEquals("portfolio_value", datasource.name());
        assertEquals("Portfolio Value", datasource.label());
        assertNotNull(datasource.fields());
        assertTrue(datasource.fields().stream().anyMatch(f -> "value".equals(f.name())));
    }

    @Test
    void priceCarryForwardAndCostBasisFallback() {
        InvestmentTransaction txn = new InvestmentTransaction();
        txn.setTradeDate(LocalDate.of(2026, 1, 15));
        txn.setQuantity(new BigDecimal("10"));
        txn.setPrice(new BigDecimal("2000"));

        when(holdingRepository.findAllWithDetails()).thenReturn(List.of(holding));
        when(transactionRepository.findByHoldingIdOrderByTradeDateAscCreatedAtAsc(holding.getId()))
                .thenReturn(List.of(txn));

        // Stub open lots at month end Jan 31: 10 qty @ 2000 cost
        when(investmentService.seedLotsFor(holding)).thenReturn(List.of());
        when(investmentService.buildOpenLotsBeforeDate(eq(holding), any(LocalDate.class), eq(false), any(), any(), any(), any()))
                .thenAnswer(inv -> {
                    LocalDate date = inv.getArgument(1);
                    if (date.isBefore(LocalDate.of(2026, 1, 15))) {
                        return List.of();
                    }
                    return List.of(new Lot(new BigDecimal("10"), new BigDecimal("2000")));
                });

        // Price stored on Jan 20 @ 2500 -> carry-forward to Jan 31
        InstrumentPrice p1 = new InstrumentPrice();
        p1.setInstrument(instrument);
        p1.setAsOf(LocalDate.of(2026, 1, 20));
        p1.setClose(new BigDecimal("2500"));

        when(priceRepository.findByInstrumentIdInOrderByAsOfAsc(anyList()))
                .thenReturn(List.of(p1));

        List<Map<String, Object>> rows = datasource.rows();
        assertFalse(rows.isEmpty());

        // Find row for Jan 31 2026
        Map<String, Object> janRow = rows.stream()
                .filter(r -> LocalDate.of(2026, 1, 31).equals(r.get("valueDate")))
                .findFirst().orElseThrow();

        // 10 qty * 2500 close price = 25000.00
        assertEquals(new BigDecimal("25000.00"), janRow.get("value"));
        assertEquals("Zerodha", janRow.get("broker"));
        assertEquals("RELIANCE", janRow.get("instrument"));
    }

    @Test
    void costBasisFallbackWhenNoStoredPriceExists() {
        InvestmentTransaction txn = new InvestmentTransaction();
        txn.setTradeDate(LocalDate.of(2026, 1, 15));
        txn.setQuantity(new BigDecimal("10"));
        txn.setPrice(new BigDecimal("2000"));

        when(holdingRepository.findAllWithDetails()).thenReturn(List.of(holding));
        when(transactionRepository.findByHoldingIdOrderByTradeDateAscCreatedAtAsc(holding.getId()))
                .thenReturn(List.of(txn));

        when(investmentService.seedLotsFor(holding)).thenReturn(List.of());
        when(investmentService.buildOpenLotsBeforeDate(eq(holding), any(LocalDate.class), eq(false), any(), any(), any(), any()))
                .thenAnswer(inv -> {
                    LocalDate date = inv.getArgument(1);
                    if (date.isBefore(LocalDate.of(2026, 1, 15))) {
                        return List.of();
                    }
                    return List.of(new Lot(new BigDecimal("10"), new BigDecimal("2000")));
                });

        // No stored price history
        when(priceRepository.findByInstrumentIdInOrderByAsOfAsc(anyList()))
                .thenReturn(List.of());

        List<Map<String, Object>> rows = datasource.rows();
        assertFalse(rows.isEmpty());

        Map<String, Object> janRow = rows.stream()
                .filter(r -> LocalDate.of(2026, 1, 31).equals(r.get("valueDate")))
                .findFirst().orElseThrow();

        // Fallback to cost basis: 10 * 2000 = 20000.00
        assertEquals(new BigDecimal("20000.00"), janRow.get("value"));
    }
}
