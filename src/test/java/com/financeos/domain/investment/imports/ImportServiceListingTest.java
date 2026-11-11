package com.financeos.domain.investment.imports;

import com.financeos.api.investment.dto.ImportPreviewResponse;
import com.financeos.core.security.UserContext;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.account.AccountType;
import com.financeos.domain.instrument.Instrument;
import com.financeos.domain.instrument.InstrumentAliasRepository;
import com.financeos.domain.instrument.InstrumentRepointMap;
import com.financeos.domain.instrument.InstrumentRepository;
import com.financeos.domain.instrument.InstrumentType;
import com.financeos.domain.instrument.UserInstrumentRepoint;
import com.financeos.domain.instrument.UserInstrumentRepointRepository;
import com.financeos.domain.instrument.search.InstrumentSearchService;
import com.financeos.domain.investment.InvestmentTransactionRepository;
import com.financeos.domain.investment.InvestmentTransactionType;
import com.financeos.domain.holding.HoldingRepository;
import com.financeos.domain.investment.dividend.DividendRepository;
import com.financeos.domain.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * One company on NSE and BSE is one holding: an import by ISIN lands on the listing the user holds, whatever
 * exchange the file names, also after a switch there and back (the repoint map never bounces it).
 */
class ImportServiceListingTest {

    private static final String ISIN = "INE002A01018";
    private final UUID user = UUID.randomUUID();
    private final UUID brokerAccountId = UUID.randomUUID();
    private ImportParser parser;
    private InstrumentRepository instrumentRepository;
    private ImportService service;
    private InstrumentRepointMap map;
    private final List<UserInstrumentRepoint> mapRows = new ArrayList<>();
    private Instrument nse;
    private Instrument bse;

    @BeforeEach
    void setUp() {
        parser = mock(ImportParser.class);
        when(parser.source()).thenReturn(ImportSource.zerodha_tradebook);
        instrumentRepository = mock(InstrumentRepository.class);
        AccountRepository accounts = mock(AccountRepository.class);
        InvestmentTransactionRepository transactions = mock(InvestmentTransactionRepository.class);
        when(transactions.findFilteredTransactions(any(), any(), any(), any(), any()))
                .thenReturn(org.springframework.data.domain.Page.empty());
        service = new ImportService(List.of(parser), instrumentRepository, mock(InstrumentAliasRepository.class),
                mock(HoldingRepository.class), transactions, mock(DividendRepository.class), accounts,
                mock(UserRepository.class), mock(InstrumentSearchService.class), mock(ApplicationEventPublisher.class));
        Account broker = new Account();
        broker.setId(brokerAccountId);
        broker.setType(AccountType.broker);
        when(accounts.findByIdAndUser_Id(eq(brokerAccountId), any())).thenReturn(Optional.of(broker));
        UserContext.setCurrentUserId(user);

        nse = row("NSE", 200);
        bse = row("BSE", 100);
        when(instrumentRepository.findByIsinIn(any())).thenReturn(List.of(nse, bse));
        when(instrumentRepository.findById(any())).thenAnswer(inv ->
                java.util.stream.Stream.of(nse, bse).filter(i -> i.getId().equals(inv.getArgument(0))).findFirst());

        UserInstrumentRepointRepository repoRepository = mock(UserInstrumentRepointRepository.class);
        when(repoRepository.findByUserIdAndFromInstrumentId(any(), any())).thenAnswer(inv -> mapRows.stream()
                .filter(r -> r.getFromInstrumentId().equals(inv.getArgument(1))).findFirst());
        when(repoRepository.findByUserIdAndToInstrumentId(any(), any())).thenAnswer(inv -> mapRows.stream()
                .filter(r -> r.getToInstrumentId().equals(inv.getArgument(1))).toList());
        when(repoRepository.save(any())).thenAnswer(inv -> {
            UserInstrumentRepoint r = inv.getArgument(0);
            if (!mapRows.contains(r)) {
                mapRows.add(r);
            }
            return r;
        });
        org.mockito.Mockito.doAnswer(inv -> mapRows.remove((UserInstrumentRepoint) inv.getArgument(0)))
                .when(repoRepository).delete(any());
        map = new InstrumentRepointMap(repoRepository, instrumentRepository);
        service.setRepointMap(map);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private static Instrument row(String exchange, long createdAtSeconds) {
        Instrument i = new Instrument();
        i.setId(UUID.randomUUID());
        i.setType(InstrumentType.stock);
        i.setName("Reliance Industries");
        i.setSymbol("RELIANCE");
        i.setExchange(exchange);
        i.setIsin(ISIN);
        i.setCreatedAt(Instant.ofEpochSecond(createdAtSeconds));
        return i;
    }

    private UUID previewMatch(String fileExchange) {
        when(parser.parse(any(InputStream.class), any(ParseContext.class))).thenReturn(List.of(new ParsedRow(1, "trade",
                InvestmentTransactionType.buy, "RELIANCE", ISIN, "Reliance", fileExchange, BigDecimal.TEN, BigDecimal.ONE,
                LocalDate.of(2026, 1, 5), null, "ref-1", null, null)));
        ImportPreviewResponse response = service.preview(new ByteArrayInputStream(new byte[0]),
                ImportSource.zerodha_tradebook, brokerAccountId);
        return response.rows().get(0).matchedInstrument().id();
    }

    @Test
    void aBseMarkedRowForACompanyHeldOnNseLandsOnTheNseHolding() {
        when(instrumentRepository.findHeldInstrumentIds(eq(user), any())).thenReturn(List.of(nse.getId()));
        assertEquals(nse.getId(), previewMatch("BSE"));
    }

    @Test
    void withNoHoldingTheFilesExchangeThenTheDefaultDecides() {
        when(instrumentRepository.findHeldInstrumentIds(eq(user), any())).thenReturn(List.of());
        assertEquals(bse.getId(), previewMatch("BSE"));
        assertEquals(nse.getId(), previewMatch(null));
    }

    @Test
    void afterASwitchThereAndBackAnIsinImportLandsOnTheCurrentHolding() {
        // NSE -> BSE: the user holds the BSE row and the map sends NSE imports there.
        map.record(user, nse.getId(), bse.getId());
        when(instrumentRepository.findHeldInstrumentIds(eq(user), any())).thenReturn(List.of(bse.getId()));
        assertEquals(bse.getId(), previewMatch("NSE"));

        // BSE -> NSE: a revert, so no record is left that would bounce it back.
        map.record(user, bse.getId(), nse.getId());
        assertTrue(mapRows.isEmpty());
        when(instrumentRepository.findHeldInstrumentIds(eq(user), any())).thenReturn(List.of(nse.getId()));
        assertEquals(nse.getId(), previewMatch("BSE"));
        assertEquals(nse.getId(), previewMatch(null));
    }
}
