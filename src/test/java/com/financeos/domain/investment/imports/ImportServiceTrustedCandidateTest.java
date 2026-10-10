package com.financeos.domain.investment.imports;

import com.financeos.api.instrument.dto.InstrumentCandidate;
import com.financeos.api.instrument.dto.InstrumentResponse;
import com.financeos.api.instrument.dto.ResolveInstrumentRequest;
import com.financeos.api.investment.dto.ImportCommitRequest;
import com.financeos.api.investment.dto.ImportPreviewResponse;
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
import com.financeos.domain.investment.dividend.DividendRepository;
import com.financeos.domain.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The import path's catalog writes take only what a search provider returned (never the user's file),
 * a catalog candidate is used as it is, and the user's repoint applies after any match.
 */
class ImportServiceTrustedCandidateTest {

    private final UUID user = UUID.randomUUID();
    private final UUID brokerAccountId = UUID.randomUUID();
    private ImportParser parser;
    private InstrumentRepository instrumentRepository;
    private HoldingRepository holdingRepository;
    private InstrumentSearchService search;
    private ImportService service;

    @BeforeEach
    void setUp() {
        parser = mock(ImportParser.class);
        when(parser.source()).thenReturn(ImportSource.zerodha_tradebook);
        instrumentRepository = mock(InstrumentRepository.class);
        holdingRepository = mock(HoldingRepository.class);
        AccountRepository accounts = mock(AccountRepository.class);
        InvestmentTransactionRepository transactions = mock(InvestmentTransactionRepository.class);
        when(transactions.findFilteredTransactions(any(), any(), any(), any(), any()))
                .thenReturn(org.springframework.data.domain.Page.empty());
        search = mock(InstrumentSearchService.class);
        service = new ImportService(List.of(parser), instrumentRepository, mock(InstrumentAliasRepository.class),
                holdingRepository, transactions, mock(DividendRepository.class), accounts, mock(UserRepository.class),
                search, mock(ApplicationEventPublisher.class));
        Account broker = new Account();
        broker.setId(brokerAccountId);
        broker.setType(AccountType.broker);
        when(accounts.findById(brokerAccountId)).thenReturn(Optional.of(broker));
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
        i.setName("Co " + isin);
        i.setIsin(isin);
        return i;
    }

    private void parses(String symbol, String isin) {
        when(parser.parse(any(InputStream.class), any(ParseContext.class))).thenReturn(List.of(new ParsedRow(1, "trade",
                InvestmentTransactionType.buy, symbol, isin, "From File", "NSE", BigDecimal.TEN, BigDecimal.ONE,
                LocalDate.of(2026, 1, 5), null, "ref-1", null, null)));
    }

    private ImportPreviewResponse preview() {
        return service.preview(new ByteArrayInputStream(new byte[0]), ImportSource.zerodha_tradebook, brokerAccountId);
    }

    @Test
    void aProviderCandidateWithoutAnIsinNeverGetsTheFilesIsin() {
        parses("NEWCO", "INE999X01011");
        when(instrumentRepository.findByIsin(anyString())).thenReturn(Optional.empty());
        InstrumentCandidate fromYahoo = new InstrumentCandidate("YAHOO", InstrumentType.stock, "New Co", "NEWCO", "NSE",
                null, null, "NEWCO.NS", "INR", null, null);
        when(search.catalogSearch(anyString(), any())).thenReturn(List.of(fromYahoo));
        Instrument resolved = instrument(null);
        when(search.resolve(any())).thenReturn(new InstrumentResponse(resolved.getId(), InstrumentType.stock,
                "New Co", "NEWCO", "NSE", null, null, "NEWCO.NS", "INR", null, null, null, null, null, null, null, null,
                null, false, List.of(), false, null));
        when(instrumentRepository.findById(resolved.getId())).thenReturn(Optional.of(resolved));

        preview();

        ArgumentCaptor<ResolveInstrumentRequest> sent = ArgumentCaptor.forClass(ResolveInstrumentRequest.class);
        verify(search).resolve(sent.capture());
        assertNull(sent.getValue().isin(), "the file's ISIN is not the provider's");
        assertNull(sent.getValue().existingInstrumentId());
        assertEquals("NEWCO.NS", sent.getValue().yahooSymbol());
    }

    @Test
    void aCatalogCandidateIsUsedAsItIsWithoutAResolve() {
        parses("OLDCO", null);
        when(instrumentRepository.searchInstruments(any(), any())).thenReturn(List.of());
        Instrument local = instrument("INE111A01011");
        InstrumentCandidate fromCatalog = new InstrumentCandidate("LOCAL", InstrumentType.etf, "My Own Name", "MINE", "BSE",
                local.getIsin(), null, null, "INR", null, local.getId());
        when(search.catalogSearch(anyString(), any())).thenReturn(List.of(fromCatalog));
        when(instrumentRepository.findById(local.getId())).thenReturn(Optional.of(local));

        ImportPreviewResponse response = preview();

        assertEquals(local.getId(), response.rows().get(0).matchedInstrument().id());
        verify(search, never()).resolve(any());
    }

    @Test
    void theUsersRepointAppliesAfterAnIsinMatchInPreviewAndCommit() {
        Instrument source = instrument("INE222A01011");
        Instrument target = instrument("INE222A01099");
        InstrumentRepointMap map = mock(InstrumentRepointMap.class);
        when(map.applyForCurrentUser(any())).thenAnswer(inv -> {
            Instrument i = inv.getArgument(0);
            return i != null && i.getId().equals(source.getId()) ? target : i;
        });
        service.setRepointMap(map);
        parses("SRC", source.getIsin());
        when(instrumentRepository.findByIsin(source.getIsin())).thenReturn(Optional.of(source));

        assertEquals(target.getId(), preview().rows().get(0).matchedInstrument().id());
        verify(holdingRepository, never()).findByBrokerAccountIdAndInstrumentId(any(), eq(source.getId()));

        when(instrumentRepository.findById(source.getId())).thenReturn(Optional.of(source));
        Holding holding = new Holding();
        holding.setId(UUID.randomUUID());
        holding.setInstrument(target);
        when(holdingRepository.findByBrokerAccountIdAndInstrumentId(brokerAccountId, target.getId()))
                .thenReturn(Optional.of(holding));
        service.commit(ImportSource.zerodha_tradebook, brokerAccountId, List.of(new ImportCommitRequest.CommitRowDto(1,
                false, source.getId(), null, new ImportCommitRequest.ParsedRowData("trade", InvestmentTransactionType.buy,
                BigDecimal.TEN, BigDecimal.ONE, null, LocalDate.of(2026, 1, 5), null, "ref-1", null))));
        verify(holdingRepository).findByBrokerAccountIdAndInstrumentId(brokerAccountId, target.getId());
        verify(holdingRepository, never()).findByBrokerAccountIdAndInstrumentId(brokerAccountId, source.getId());
    }
}
