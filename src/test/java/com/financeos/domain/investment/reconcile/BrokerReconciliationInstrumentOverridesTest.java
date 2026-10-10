package com.financeos.domain.investment.reconcile;

import com.financeos.api.investment.dto.ReconcilePreviewResponse;
import com.financeos.core.security.UserContext;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.account.AccountType;
import com.financeos.domain.holding.HoldingRepository;
import com.financeos.domain.instrument.Instrument;
import com.financeos.domain.instrument.InstrumentAlias;
import com.financeos.domain.instrument.InstrumentAliasRepository;
import com.financeos.domain.instrument.InstrumentOverrides;
import com.financeos.domain.instrument.InstrumentRepository;
import com.financeos.domain.instrument.InstrumentType;
import com.financeos.domain.instrument.UserInstrumentOverride;
import com.financeos.domain.instrument.search.InstrumentSearchService;
import com.financeos.domain.investment.InvestmentTransactionRepository;
import com.financeos.domain.investment.TradeSettlementClassificationRepository;
import com.financeos.domain.investment.fno.FnoTradeRepository;
import com.financeos.domain.investment.fno.FnoTradeService;
import com.financeos.domain.investment.charges.ChargeCalculator;
import com.financeos.domain.investment.imports.ZerodhaTradebookParser;
import com.financeos.domain.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Broker reconciliation matches with the user's own aliases first and shows the user's own names. */
class BrokerReconciliationInstrumentOverridesTest {

    private final UUID user = UUID.randomUUID();
    private final UUID brokerAccountId = UUID.randomUUID();
    private InstrumentRepository instrumentRepository;
    private InstrumentAliasRepository aliasRepository;
    private InstrumentSearchService searchService;
    private BrokerReconciliationService service;
    private Instrument catalogMatch;
    private Instrument repointed;

    @BeforeEach
    void setUp() {
        instrumentRepository = mock(InstrumentRepository.class);
        aliasRepository = mock(InstrumentAliasRepository.class);
        searchService = mock(InstrumentSearchService.class);
        AccountRepository accountRepository = mock(AccountRepository.class);
        InvestmentTransactionRepository transactionRepository = mock(InvestmentTransactionRepository.class);
        when(transactionRepository.findFilteredTransactions(any(), any(), any(), any(), any()))
                .thenReturn(org.springframework.data.domain.Page.empty());
        service = new BrokerReconciliationService(new ZerodhaTradebookParser(), new ZerodhaTaxPnlParser(),
                new GrowwOrderHistoryParser(), new GrowwCapitalGainsParser(), new ChargeCalculator(), instrumentRepository,
                aliasRepository, mock(HoldingRepository.class), transactionRepository, accountRepository,
                mock(UserRepository.class), searchService, mock(HoldingsSnapshotParser.class),
                mock(TradeSettlementClassificationRepository.class), mock(ApplicationEventPublisher.class),
                mock(FnoTradeRepository.class), mock(FnoTradeService.class));
        Account account = new Account();
        account.setType(AccountType.broker);
        account.setName("Zerodha");
        when(accountRepository.findById(brokerAccountId)).thenReturn(Optional.of(account));
        catalogMatch = instrument("OLDS", "Old Co");
        repointed = instrument("NEWS", "New Co");
        when(instrumentRepository.searchInstruments(any(), any())).thenReturn(List.of(catalogMatch));
        UserContext.setCurrentUserId(user);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private static Instrument instrument(String symbol, String name) {
        Instrument i = new Instrument();
        i.setId(UUID.randomUUID());
        i.setType(InstrumentType.stock);
        i.setSymbol(symbol);
        i.setName(name);
        i.setExchange("NSE");
        return i;
    }

    private ReconcilePreviewResponse.MatchedInstrumentDto match() {
        String csv = "symbol,isin,trade_date,exchange,segment,series,trade_type,auction,quantity,price,trade_id,order_id,order_execution_time\n"
                + "OLDS,,2026-02-15,NSE,EQ,EQ,buy,false,10,300.0,101,201,2026-02-15 10:00:00\n";
        ReconcilePreviewResponse preview = service.preview(Broker.zerodha, brokerAccountId,
                List.of(new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8))), List.of());
        return preview.executions().get(0).matchedInstrument();
    }

    @Test
    void theUsersOwnAliasBeatsTheCatalogSymbol() {
        InstrumentAlias own = new InstrumentAlias(repointed, "OLDS", "Old Co", "USER_EDIT");
        own.setUserId(user);
        when(aliasRepository.findFirstByOldSymbolIgnoreCaseAndUserId("OLDS", user)).thenReturn(Optional.of(own));

        assertEquals(repointed.getId(), match().id());
    }

    @Test
    void withoutOneTheCatalogSymbolMatchesAndShowsTheUsersName() {
        UserInstrumentOverride row = new UserInstrumentOverride(user, catalogMatch.getId());
        row.setName("My Old Co");
        when(searchService.currentUserOverrides()).thenReturn(InstrumentOverrides.of(List.of(row)));

        ReconcilePreviewResponse.MatchedInstrumentDto m = match();

        assertEquals(catalogMatch.getId(), m.id());
        assertEquals("My Old Co", m.name());
    }
}
