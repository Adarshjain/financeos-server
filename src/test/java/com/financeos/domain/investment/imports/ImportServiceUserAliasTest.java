package com.financeos.domain.investment.imports;

import com.financeos.api.investment.dto.ImportPreviewResponse;
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
import com.financeos.domain.investment.InvestmentTransactionType;
import com.financeos.domain.investment.dividend.DividendRepository;
import com.financeos.domain.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Import matching with per-user aliases: the importing user's own alias (written when they moved
 * their holdings to another instrument) wins over a catalog symbol match; another user's never
 * applies; catalog aliases still back up an unmatched symbol. Matches show the user's own names.
 */
class ImportServiceUserAliasTest {

    private ImportParser parser;
    private InstrumentRepository instrumentRepository;
    private InstrumentAliasRepository aliasRepository;
    private InstrumentSearchService searchService;
    private ImportService importService;
    private UUID brokerAccountId;
    private final UUID user = UUID.randomUUID();
    private Instrument catalogMatch;
    private Instrument repointed;

    @BeforeEach
    void setUp() {
        parser = mock(ImportParser.class);
        instrumentRepository = mock(InstrumentRepository.class);
        aliasRepository = mock(InstrumentAliasRepository.class);
        searchService = mock(InstrumentSearchService.class);
        InvestmentTransactionRepository transactionRepository = mock(InvestmentTransactionRepository.class);
        AccountRepository accountRepository = mock(AccountRepository.class);
        when(parser.source()).thenReturn(ImportSource.zerodha_tradebook);
        importService = new ImportService(List.of(parser), instrumentRepository, aliasRepository,
                mock(HoldingRepository.class), transactionRepository, mock(DividendRepository.class), accountRepository,
                mock(UserRepository.class), searchService, mock(ApplicationEventPublisher.class));

        brokerAccountId = UUID.randomUUID();
        Account broker = new Account();
        broker.setId(brokerAccountId);
        broker.setType(AccountType.broker);
        when(accountRepository.findById(brokerAccountId)).thenReturn(Optional.of(broker));
        when(transactionRepository.findFilteredTransactions(any(), any(), any(), any(), any()))
                .thenReturn(org.springframework.data.domain.Page.empty());

        catalogMatch = instrument("OLDS", "Old Co");
        repointed = instrument("NEWS", "New Co");
        when(instrumentRepository.searchInstruments("OLDS", null)).thenReturn(List.of(catalogMatch));
        when(parser.parse(any(InputStream.class), any(ParseContext.class))).thenReturn(List.of(new ParsedRow(1, "trade",
                InvestmentTransactionType.buy, "OLDS", null, "Old Co", "NSE", new BigDecimal("1"), new BigDecimal("10"),
                LocalDate.of(2026, 10, 1), null, null, null, null)));
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

    private ImportPreviewResponse.MatchedInstrumentDto preview() {
        return importService.preview(new ByteArrayInputStream(new byte[0]), ImportSource.zerodha_tradebook, brokerAccountId)
                .rows().get(0).matchedInstrument();
    }

    @Test
    void theUsersOwnAliasBeatsTheCatalogSymbol() {
        InstrumentAlias own = new InstrumentAlias(repointed, "OLDS", "Old Co", "USER_EDIT");
        own.setUserId(user);
        when(aliasRepository.findFirstByOldSymbolIgnoreCaseAndUserId("OLDS", user)).thenReturn(Optional.of(own));

        assertEquals(repointed.getId(), preview().id());
        verify(instrumentRepository, never()).searchInstruments("OLDS", null);
    }

    @Test
    void withoutAnOwnAliasTheCatalogSymbolMatches() {
        assertEquals(catalogMatch.getId(), preview().id());
        verify(aliasRepository, never()).findFirstByOldSymbolIgnoreCaseAndUserIdIsNull(any());
    }

    @Test
    void aCatalogAliasStillBacksUpAnUnmatchedSymbol() {
        when(instrumentRepository.searchInstruments("OLDS", null)).thenReturn(List.of());
        when(aliasRepository.findFirstByOldSymbolIgnoreCaseAndUserIdIsNull("OLDS"))
                .thenReturn(Optional.of(new InstrumentAlias(repointed, "OLDS", "Old Co", "IMPORT_RESOLVE")));

        assertEquals(repointed.getId(), preview().id());
    }

    @Test
    void anonymousImportsNeverLookUpAUsersAlias() {
        UserContext.clear();
        assertEquals(catalogMatch.getId(), preview().id());
        verify(aliasRepository, never()).findFirstByOldSymbolIgnoreCaseAndUserId(any(), any());
    }

    @Test
    void theMatchShowsTheUsersOwnNames() {
        UserInstrumentOverride row = new UserInstrumentOverride(user, catalogMatch.getId());
        row.setName("My Old Co");
        row.setType(InstrumentType.etf);
        when(searchService.currentUserOverrides()).thenReturn(InstrumentOverrides.of(List.of(row)));

        ImportPreviewResponse.MatchedInstrumentDto match = preview();

        assertEquals("My Old Co", match.name());
        assertEquals(InstrumentType.etf, match.type());
        assertEquals("OLDS", match.symbol());
    }
}
