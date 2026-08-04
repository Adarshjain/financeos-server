package com.financeos.domain.investment.imports;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.financeos.api.investment.dto.ImportPreviewResponse;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.account.AccountType;
import com.financeos.domain.holding.HoldingRepository;
import com.financeos.domain.instrument.*;
import com.financeos.domain.instrument.search.InstrumentSearchService;
import com.financeos.domain.investment.InvestmentTransactionRepository;
import com.financeos.domain.investment.dividend.DividendRepository;
import com.financeos.domain.user.UserRepository;
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

class ImportServiceTest {

    private ImportParser mockParser;
    private InstrumentRepository instrumentRepository;
    private InstrumentAliasRepository aliasRepository;
    private HoldingRepository holdingRepository;
    private InvestmentTransactionRepository transactionRepository;
    private DividendRepository dividendRepository;
    private AccountRepository accountRepository;
    private UserRepository userRepository;
    private InstrumentSearchService instrumentSearchService;
    private ApplicationEventPublisher eventPublisher;

    private ImportService importService;
    private UUID brokerAccountId;

    @BeforeEach
    void setUp() {
        mockParser = mock(ImportParser.class);
        instrumentRepository = mock(InstrumentRepository.class);
        aliasRepository = mock(InstrumentAliasRepository.class);
        holdingRepository = mock(HoldingRepository.class);
        transactionRepository = mock(InvestmentTransactionRepository.class);
        dividendRepository = mock(DividendRepository.class);
        accountRepository = mock(AccountRepository.class);
        userRepository = mock(UserRepository.class);
        instrumentSearchService = mock(InstrumentSearchService.class);
        eventPublisher = mock(ApplicationEventPublisher.class);

        when(mockParser.source()).thenReturn(ImportSource.zerodha_tradebook);

        importService = new ImportService(
                List.of(mockParser),
                instrumentRepository,
                aliasRepository,
                holdingRepository,
                transactionRepository,
                dividendRepository,
                accountRepository,
                userRepository,
                instrumentSearchService,
                eventPublisher
        );

        brokerAccountId = UUID.randomUUID();
        Account brokerAccount = new Account();
        brokerAccount.setId(brokerAccountId);
        brokerAccount.setType(AccountType.broker);

        when(accountRepository.findById(brokerAccountId)).thenReturn(Optional.of(brokerAccount));
        when(transactionRepository.findFilteredTransactions(any(), any(), any(), any(), any())).thenReturn(org.springframework.data.domain.Page.empty());
    }

    @Test
    void testPreviewUnresolvedIsinLeavesYahooSymbolNull() {
        ParsedRow deadIsinRow = new ParsedRow(
                1,
                "trade",
                com.financeos.domain.investment.InvestmentTransactionType.buy,
                "HDFC",
                "INE001A01036",
                "Housing Development Finance Corp",
                "NSE",
                new BigDecimal("10"),
                new BigDecimal("2500.00"),
                LocalDate.now(),
                null,
                null,
                null,
                null
        );

        when(mockParser.parse(any(InputStream.class), any(ParseContext.class))).thenReturn(List.of(deadIsinRow));
        when(instrumentRepository.findByIsin("INE001A01036")).thenReturn(Optional.empty());
        when(instrumentRepository.searchInstruments("HDFC", null)).thenReturn(List.of());
        when(instrumentSearchService.catalogSearch(anyString(), any(InstrumentType.class))).thenReturn(List.of());

        ArgumentCaptor<Instrument> captor = ArgumentCaptor.forClass(Instrument.class);
        when(instrumentRepository.save(captor.capture())).thenAnswer(invocation -> {
            Instrument saved = invocation.getArgument(0);
            saved.setId(UUID.randomUUID());
            return saved;
        });

        ImportPreviewResponse response = importService.preview(new ByteArrayInputStream(new byte[0]), ImportSource.zerodha_tradebook, brokerAccountId);

        assertEquals(1, response.summary().matched());
        Instrument savedInst = captor.getValue();
        assertEquals("HDFC", savedInst.getSymbol());
        assertEquals("INE001A01036", savedInst.getIsin());
        assertNull(savedInst.getYahooSymbol(), "yahooSymbol must be null for delisted/unresolved ISIN rows");
    }

    @Test
    void testPreviewCollisionRecoveryReuseLocalSymbolMatch() {
        ParsedRow collidingRow = new ParsedRow(
                1,
                "trade",
                com.financeos.domain.investment.InvestmentTransactionType.buy,
                "HDFCBANK",
                "INE001A01036",
                "HDFC Merged",
                "NSE",
                new BigDecimal("10"),
                new BigDecimal("1500.00"),
                LocalDate.now(),
                null,
                null,
                null,
                null
        );

        when(mockParser.parse(any(InputStream.class), any(ParseContext.class))).thenReturn(List.of(collidingRow));
        when(instrumentRepository.findByIsin("INE001A01036")).thenReturn(Optional.empty());
        when(instrumentSearchService.catalogSearch(anyString(), any(InstrumentType.class))).thenReturn(List.of());

        // Save fails with unique constraint exception
        when(instrumentRepository.save(any(Instrument.class))).thenThrow(new RuntimeException("Unique constraint violation"));

        Instrument existingAcquirer = new Instrument();
        existingAcquirer.setId(UUID.randomUUID());
        existingAcquirer.setSymbol("HDFCBANK");
        existingAcquirer.setExchange("NSE");
        existingAcquirer.setIsin(""); // blank ISIN, compatible

        when(instrumentRepository.searchInstruments("HDFCBANK", null)).thenReturn(List.of(existingAcquirer));

        ImportPreviewResponse response = importService.preview(new ByteArrayInputStream(new byte[0]), ImportSource.zerodha_tradebook, brokerAccountId);

        assertEquals(1, response.summary().matched());
        assertEquals(existingAcquirer.getId(), response.rows().get(0).matchedInstrument().id());
    }
}
