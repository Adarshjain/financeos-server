package com.financeos.domain.report.datasource.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.financeos.domain.account.Account;
import com.financeos.domain.holding.Holding;
import com.financeos.domain.holding.HoldingRepository;
import com.financeos.domain.instrument.Instrument;
import com.financeos.domain.instrument.InstrumentPrice;
import com.financeos.domain.instrument.InstrumentPriceRepository;
import com.financeos.domain.instrument.InstrumentType;
import com.financeos.domain.instrument.corporateaction.CorporateAction;
import com.financeos.domain.instrument.corporateaction.CorporateActionRepository;
import com.financeos.domain.instrument.corporateaction.CorporateActionType;
import com.financeos.domain.investment.InvestmentService;
import com.financeos.domain.investment.InvestmentService.Lot;
import com.financeos.domain.investment.InvestmentTransaction;
import com.financeos.domain.investment.InvestmentTransactionRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PortfolioValueDelistedTest {

    @Test
    void delistedHoldingIsValuedAtZeroFromItsExDateAndLoadedOncePerHolding() {
        HoldingRepository holdings = mock(HoldingRepository.class);
        InvestmentTransactionRepository txRepo = mock(InvestmentTransactionRepository.class);
        InstrumentPriceRepository prices = mock(InstrumentPriceRepository.class);
        CorporateActionRepository cas = mock(CorporateActionRepository.class);
        InvestmentService investment = mock(InvestmentService.class);
        PortfolioValueDatasource ds = new PortfolioValueDatasource(holdings, txRepo, prices, cas, investment);

        Account account = new Account();
        account.setId(UUID.randomUUID());
        account.setName("Zerodha");
        Instrument instrument = new Instrument();
        instrument.setId(UUID.randomUUID());
        instrument.setName("DEADCO");
        instrument.setType(InstrumentType.stock);
        Holding holding = new Holding();
        holding.setId(UUID.randomUUID());
        holding.setBrokerAccount(account);
        holding.setInstrument(instrument);
        com.financeos.domain.user.User owner = new com.financeos.domain.user.User();
        owner.setId(UUID.randomUUID());
        holding.setUser(owner);

        InvestmentTransaction txn = new InvestmentTransaction();
        txn.setTradeDate(LocalDate.of(2026, 1, 15));
        txn.setQuantity(new BigDecimal("10"));
        txn.setPrice(new BigDecimal("100"));
        when(holdings.findAllWithDetailsOfUser(any())).thenReturn(List.of(holding));
        txn.setHolding(holding);
        when(txRepo.findByHoldingIdInOrderByTradeDateAscCreatedAtAsc(any())).thenReturn(List.of(txn));
        when(investment.seedLotsByHolding(any())).thenReturn(Map.of());
        when(investment.buildOpenLotsBeforeDate(eq(holding), any(LocalDate.class), eq(false), any(), any(), any(), any(), any()))
                .thenAnswer(i -> ((LocalDate) i.getArgument(1)).isBefore(LocalDate.of(2026, 1, 15))
                        ? List.of() : List.of(new Lot(new BigDecimal("10"), new BigDecimal("100"))));
        InstrumentPrice p = new InstrumentPrice();
        p.setInstrument(instrument);
        p.setAsOf(LocalDate.of(2026, 1, 20));
        p.setClose(new BigDecimal("120"));
        when(prices.findVisibleByInstrumentIds(anyList(), any())).thenReturn(List.of(p));

        CorporateAction delisted = new CorporateAction();
        delisted.setInstrument(instrument);
        delisted.setType(CorporateActionType.delisted);
        delisted.setExDate(LocalDate.of(2026, 3, 10));
        when(cas.findOwnedByInstrumentIds(eq(owner.getId()), anyCollection())).thenReturn(List.of(delisted));

        List<Map<String, Object>> rows = ds.rows();

        assertTrue(rows.stream().anyMatch(r -> LocalDate.of(2026, 1, 31).equals(r.get("valueDate"))
                && new BigDecimal("1200.00").equals(r.get("value"))));
        assertTrue(rows.stream().anyMatch(r -> LocalDate.of(2026, 2, 28).equals(r.get("valueDate"))));
        assertFalse(rows.stream().anyMatch(r -> ((LocalDate) r.get("valueDate")).isAfter(LocalDate.of(2026, 3, 9))));
        verify(cas, times(1)).findOwnedByInstrumentIds(eq(owner.getId()), anyCollection());
        assertEquals(true, PortfolioValueDatasource.delistedOnOrBefore(List.of(delisted), LocalDate.of(2026, 3, 10)));
        assertEquals(false, PortfolioValueDatasource.delistedOnOrBefore(List.of(delisted), LocalDate.of(2026, 3, 9)));
    }
}
