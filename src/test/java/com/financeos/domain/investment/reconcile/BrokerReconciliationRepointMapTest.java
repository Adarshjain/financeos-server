package com.financeos.domain.investment.reconcile;

import com.financeos.api.investment.dto.ReconcileCommitRequest;
import com.financeos.api.investment.dto.ReconcilePreviewResponse;
import com.financeos.core.security.UserContext;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.account.AccountType;
import com.financeos.domain.holding.Holding;
import com.financeos.domain.holding.HoldingRepository;
import com.financeos.domain.instrument.Instrument;
import com.financeos.domain.instrument.InstrumentAliasRepository;
import com.financeos.domain.instrument.InstrumentRepointMap;
import com.financeos.domain.instrument.InstrumentRepository;
import com.financeos.domain.instrument.InstrumentType;
import com.financeos.domain.instrument.search.InstrumentSearchService;
import com.financeos.domain.investment.InvestmentTransactionRepository;
import com.financeos.domain.investment.InvestmentTransactionType;
import com.financeos.domain.investment.TradeSettlementClassificationRepository;
import com.financeos.domain.investment.charges.ChargeCalculator;
import com.financeos.domain.investment.fno.FnoTradeRepository;
import com.financeos.domain.investment.fno.FnoTradeService;
import com.financeos.domain.investment.imports.ZerodhaTradebookParser;
import com.financeos.domain.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Broker reconciliation applies the user's repoint after any instrument match (preview, commit, and the
 * duplicate check of a skipped row), so a re-import of an instrument they moved off lands on — and is
 * de-duplicated against — the one they moved to.
 */
class BrokerReconciliationRepointMapTest {

    private final UUID user = UUID.randomUUID();
    private final UUID brokerAccountId = UUID.randomUUID();
    private InstrumentRepository instrumentRepository;
    private HoldingRepository holdingRepository;
    private BrokerReconciliationService service;
    private Instrument source;
    private Instrument target;

    @BeforeEach
    void setUp() {
        instrumentRepository = mock(InstrumentRepository.class);
        holdingRepository = mock(HoldingRepository.class);
        AccountRepository accountRepository = mock(AccountRepository.class);
        InvestmentTransactionRepository transactionRepository = mock(InvestmentTransactionRepository.class);
        when(transactionRepository.findFilteredTransactions(any(), any(), any(), any(), any()))
                .thenReturn(org.springframework.data.domain.Page.empty());
        service = new BrokerReconciliationService(new ZerodhaTradebookParser(), new ZerodhaTaxPnlParser(),
                new GrowwOrderHistoryParser(), new GrowwCapitalGainsParser(), new ChargeCalculator(), instrumentRepository,
                mock(InstrumentAliasRepository.class), holdingRepository, transactionRepository, accountRepository,
                mock(UserRepository.class), mock(InstrumentSearchService.class), mock(HoldingsSnapshotParser.class),
                mock(TradeSettlementClassificationRepository.class), mock(ApplicationEventPublisher.class),
                mock(FnoTradeRepository.class), mock(FnoTradeService.class));
        Account account = new Account();
        account.setId(brokerAccountId);
        account.setType(AccountType.broker);
        account.setName("Zerodha");
        when(accountRepository.findById(brokerAccountId)).thenReturn(Optional.of(account));
        source = instrument("INE333A01011");
        target = instrument("INE333A01099");
        when(instrumentRepository.findByIsin(source.getIsin())).thenReturn(Optional.of(source));
        when(instrumentRepository.findById(source.getId())).thenReturn(Optional.of(source));
        InstrumentRepointMap map = mock(InstrumentRepointMap.class);
        when(map.applyForCurrentUser(any())).thenAnswer(inv -> {
            Instrument i = inv.getArgument(0);
            return i != null && i.getId().equals(source.getId()) ? target : i;
        });
        when(map.resolve(eq(user), any())).thenAnswer(inv -> {
            UUID id = inv.getArgument(1);
            return source.getId().equals(id) ? target.getId() : id;
        });
        service.setRepointMap(map);
        UserContext.setCurrentUserId(user);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private static Instrument instrument(String isin) {
        Instrument i = new Instrument();
        i.setId(UUID.randomUUID());
        i.setType(InstrumentType.stock);
        i.setSymbol("SRC");
        i.setName("Source Co");
        i.setExchange("NSE");
        i.setIsin(isin);
        return i;
    }

    private ReconcileCommitRequest.CommitExecutionDto execution(UUID instrumentId, boolean skip) {
        return new ReconcileCommitRequest.CommitExecutionDto(1, LocalDate.of(2026, 2, 15), InvestmentTransactionType.buy,
                null, "SRC", source.getIsin(), "NSE", BigDecimal.TEN, new BigDecimal("300"), null, "zerodha_trade_101",
                instrumentId, null, skip);
    }

    @Test
    void previewMatchesTheIsinAndShowsTheInstrumentTheUserMovedTo() {
        String csv = "symbol,isin,trade_date,exchange,segment,series,trade_type,auction,quantity,price,trade_id,order_id,order_execution_time\n"
                + "SRC," + source.getIsin() + ",2026-02-15,NSE,EQ,EQ,buy,false,10,300.0,101,201,2026-02-15 10:00:00\n";
        ReconcilePreviewResponse preview = service.preview(Broker.zerodha, brokerAccountId,
                List.of(new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8))), List.of());

        assertEquals(target.getId(), preview.executions().get(0).matchedInstrument().id());
    }

    @Test
    void aCommitNamingTheOldInstrumentLandsOnTheTargetHolding() {
        Holding holding = new Holding();
        holding.setId(UUID.randomUUID());
        holding.setInstrument(target);
        when(holdingRepository.findByBrokerAccountIdAndInstrumentId(brokerAccountId, target.getId()))
                .thenReturn(Optional.of(holding));

        service.commit(new ReconcileCommitRequest(Broker.zerodha, brokerAccountId,
                List.of(execution(source.getId(), false)), List.of(), List.of()));

        verify(holdingRepository).findByBrokerAccountIdAndInstrumentId(brokerAccountId, target.getId());
        verify(holdingRepository, never()).findByBrokerAccountIdAndInstrumentId(brokerAccountId, source.getId());
    }
}
