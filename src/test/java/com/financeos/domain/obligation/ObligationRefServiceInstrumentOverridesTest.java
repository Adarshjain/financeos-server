package com.financeos.domain.obligation;

import com.financeos.api.transaction.dto.ObligationRef;
import com.financeos.domain.holding.Holding;
import com.financeos.domain.instrument.AssetClassOverrideService;
import com.financeos.domain.instrument.Instrument;
import com.financeos.domain.instrument.InstrumentOverrides;
import com.financeos.domain.instrument.UserInstrumentOverride;
import com.financeos.domain.investment.dividend.Dividend;
import com.financeos.domain.investment.dividend.DividendRepository;
import com.financeos.domain.investment.dividend.DividendType;
import com.financeos.domain.lending.LendingRepository;
import com.financeos.domain.loan.LoanChargeRepository;
import com.financeos.domain.loan.LoanEventRepository;
import com.financeos.domain.loan.LoanPaymentRepository;
import com.financeos.domain.transaction.Transaction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** A dividend's obligation label names the instrument as the reading user does. */
class ObligationRefServiceInstrumentOverridesTest {

    private DividendRepository dividendRepository;
    private AssetClassOverrideService overrideService;
    private ObligationRefService service;
    private Instrument fund;
    private final UUID user = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        dividendRepository = mock(DividendRepository.class);
        overrideService = mock(AssetClassOverrideService.class);
        service = new ObligationRefService(mock(LendingRepository.class), mock(LoanPaymentRepository.class),
                mock(LoanEventRepository.class), mock(LoanChargeRepository.class), dividendRepository, overrideService);
        fund = new Instrument();
        fund.setId(UUID.randomUUID());
        fund.setName("Axis Bluechip Fund Direct Growth");
        fund.setSymbol("INF846K01DP8");
    }

    private Dividend dividendOn(Transaction t) {
        Dividend d = new Dividend();
        d.setId(UUID.randomUUID());
        d.setType(DividendType.dividend);
        d.setAmount(BigDecimal.TEN);
        d.setHolding(new Holding(null, fund, null));
        d.setTransaction(t);
        return d;
    }

    private static Transaction txn() {
        Transaction t = new Transaction();
        t.setId(UUID.randomUUID());
        return t;
    }

    @Test
    void theLabelUsesTheReadersShortSymbolOrName() {
        UserInstrumentOverride row = new UserInstrumentOverride(user, fund.getId());
        row.setSymbol("AXBC");
        InstrumentOverrides overrides = InstrumentOverrides.of(List.of(row));
        assertEquals("Dividend · AXBC", ObligationRefService.toRef(dividendOn(txn()), overrides).label());

        row.setSymbol(null);
        row.setName("Axis Bluechip");
        assertEquals("Dividend · Axis Bluechip",
                ObligationRefService.toRef(dividendOn(txn()), InstrumentOverrides.of(List.of(row))).label());
        assertEquals("Dividend · Axis Bluechip Fund Direct Growth",
                ObligationRefService.toRef(dividendOn(txn())).label(), "the catalog without overrides");
    }

    @Test
    void refsForLoadTheReadersOverridesOnlyWhenADividendIsLinked() {
        Transaction t = txn();
        UserInstrumentOverride row = new UserInstrumentOverride(user, fund.getId());
        row.setName("Axis Bluechip");
        when(overrideService.overridesForCurrentUser()).thenReturn(InstrumentOverrides.of(List.of(row)));
        when(dividendRepository.findWithHoldingByTransactionIdIn(any())).thenReturn(List.of(dividendOn(t)));

        List<ObligationRef> refs = service.refsFor(t.getId());

        assertEquals("Dividend · Axis Bluechip", refs.get(0).label());
    }

    @Test
    void noLinkedDividendMeansNoOverrideLookup() {
        when(dividendRepository.findWithHoldingByTransactionIdIn(any())).thenReturn(List.of());
        service.refsFor(UUID.randomUUID());
        verify(overrideService, never()).overridesForCurrentUser();
    }

    @Test
    void aMergeRepointLabelsMovedDividendsTheSameWay() {
        Transaction from = txn();
        Transaction to = txn();
        UserInstrumentOverride row = new UserInstrumentOverride(user, fund.getId());
        row.setName("Axis Bluechip");
        when(overrideService.overridesForCurrentUser()).thenReturn(InstrumentOverrides.of(List.of(row)));
        when(dividendRepository.findWithHoldingByTransactionIdIn(any())).thenReturn(List.of(dividendOn(from)));

        assertEquals(List.of("Dividend · Axis Bluechip"), service.repoint(from, to));
    }
}
