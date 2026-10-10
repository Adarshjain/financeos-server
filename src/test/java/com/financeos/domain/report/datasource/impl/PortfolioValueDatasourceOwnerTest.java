package com.financeos.domain.report.datasource.impl;

import com.financeos.core.security.UserContext;
import com.financeos.domain.account.Account;
import com.financeos.domain.holding.Holding;
import com.financeos.domain.holding.HoldingRepository;
import com.financeos.domain.instrument.Instrument;
import com.financeos.domain.instrument.InstrumentOverrides;
import com.financeos.domain.instrument.InstrumentPrice;
import com.financeos.domain.instrument.InstrumentPriceRepository;
import com.financeos.domain.instrument.InstrumentType;
import com.financeos.domain.instrument.UserInstrumentOverride;
import com.financeos.domain.investment.InvestmentService;
import com.financeos.domain.investment.InvestmentService.Lot;
import com.financeos.domain.investment.InvestmentTransaction;
import com.financeos.domain.investment.InvestmentTransactionRepository;
import com.financeos.domain.user.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Outside a request (no signed-in user) the portfolio-value rows take the prices AND the instrument
 * overrides of the same user — the holdings' owner — and the price lookup's IN list is chunked.
 */
class PortfolioValueDatasourceOwnerTest {

    private HoldingRepository holdingRepository;
    private InvestmentTransactionRepository transactionRepository;
    private InstrumentPriceRepository priceRepository;
    private InvestmentService investmentService;
    private PortfolioValueDatasource datasource;
    private final UUID owner = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        holdingRepository = mock(HoldingRepository.class);
        transactionRepository = mock(InvestmentTransactionRepository.class);
        priceRepository = mock(InstrumentPriceRepository.class);
        investmentService = mock(InvestmentService.class);
        datasource = new PortfolioValueDatasource(holdingRepository, transactionRepository, priceRepository,
                mock(com.financeos.domain.instrument.corporateaction.CorporateActionRepository.class), investmentService);
        UserContext.clear();
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private Holding holding(Instrument instrument) {
        User user = new User();
        user.setId(owner);
        Account account = new Account();
        account.setId(UUID.randomUUID());
        account.setName("Zerodha");
        Holding h = new Holding();
        h.setId(UUID.randomUUID());
        h.setBrokerAccount(account);
        h.setInstrument(instrument);
        h.setUser(user);
        return h;
    }

    private static Instrument instrument(String name) {
        Instrument i = new Instrument();
        i.setId(UUID.randomUUID());
        i.setName(name);
        i.setType(InstrumentType.stock);
        return i;
    }

    @Test
    void theOwnersOverridesGoWithTheOwnersPrices() {
        Instrument reliance = instrument("RELIANCE");
        Holding holding = holding(reliance);
        InvestmentTransaction txn = new InvestmentTransaction();
        txn.setTradeDate(LocalDate.of(2026, 1, 15));
        txn.setQuantity(BigDecimal.TEN);
        txn.setPrice(new BigDecimal("2000"));
        when(holdingRepository.findAllWithDetails()).thenReturn(List.of(holding));
        when(transactionRepository.findByHoldingIdOrderByTradeDateAscCreatedAtAsc(holding.getId())).thenReturn(List.of(txn));
        when(investmentService.seedLotsFor(holding)).thenReturn(List.of());
        when(investmentService.buildOpenLotsBeforeDate(eq(holding), any(LocalDate.class), eq(false), any(), any(), any(), any()))
                .thenReturn(List.of(new Lot(BigDecimal.TEN, new BigDecimal("2000"))));
        InstrumentPrice ownersPrice = InstrumentPrice.manual(reliance, owner, LocalDate.of(2026, 1, 20), new BigDecimal("2500"));
        when(priceRepository.findVisibleByInstrumentIds(anyList(), eq(owner))).thenReturn(List.of(ownersPrice));
        UserInstrumentOverride row = new UserInstrumentOverride(owner, reliance.getId());
        row.setName("Owner's Reliance");
        when(investmentService.instrumentOverridesOf(owner)).thenReturn(InstrumentOverrides.of(List.of(row)));

        Map<String, Object> jan = datasource.rows().stream()
                .filter(r -> LocalDate.of(2026, 1, 31).equals(r.get("valueDate"))).findFirst().orElseThrow();

        assertEquals("Owner's Reliance", jan.get("instrument"));
        assertEquals(new BigDecimal("25000.00"), jan.get("value"));
        verify(investmentService, never()).instrumentOverrides();
    }

    @Test
    void thePriceLookupIsChunkedBelowOraclesInListLimit() {
        List<Holding> holdings = new ArrayList<>();
        for (int i = 0; i < 901; i++) {
            holdings.add(holding(instrument("I" + i)));
        }
        when(holdingRepository.findAllWithDetails()).thenReturn(holdings);
        when(transactionRepository.findByHoldingIdOrderByTradeDateAscCreatedAtAsc(any())).thenReturn(List.of());
        when(investmentService.seedLotsFor(any())).thenReturn(List.of());

        datasource.rows();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<UUID>> ids = ArgumentCaptor.forClass(Collection.class);
        verify(priceRepository, times(2)).findVisibleByInstrumentIds(ids.capture(), eq(owner));
        assertEquals(900, ids.getAllValues().get(0).size());
        assertEquals(1, ids.getAllValues().get(1).size());
    }
}
