package com.financeos.domain.investment.reconcile;

import com.financeos.api.investment.dto.ImportCommitRequest.CreateInstrumentDto;
import com.financeos.api.investment.dto.ImportCommitResponse;
import com.financeos.api.investment.dto.ReconcileCommitRequest;
import com.financeos.api.investment.dto.ReconcilePreviewResponse;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.account.AccountType;
import com.financeos.domain.holding.HoldingRepository;
import com.financeos.domain.instrument.Instrument;
import com.financeos.domain.instrument.InstrumentAliasRepository;
import com.financeos.domain.instrument.InstrumentRepository;
import com.financeos.domain.instrument.InstrumentType;
import com.financeos.domain.instrument.search.InstrumentSearchService;
import com.financeos.domain.investment.InvestmentTransactionRepository;
import com.financeos.domain.investment.InvestmentTransactionType;
import com.financeos.domain.investment.SettlementType;
import com.financeos.domain.investment.TradeSettlementClassificationRepository;
import com.financeos.domain.investment.charges.ChargeCalculator;
import com.financeos.domain.investment.fno.FnoTradeRepository;
import com.financeos.domain.investment.fno.FnoTradeService;
import com.financeos.domain.investment.imports.ZerodhaTradebookParser;
import com.financeos.domain.user.UserRepository;
import org.apache.poi.xssf.usermodel.XSSFRow;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BrokerReconciliationSuggestionTest {

    @Mock private InstrumentRepository instrumentRepository;
    @Mock private InstrumentAliasRepository aliasRepository;
    @Mock private HoldingRepository holdingRepository;
    @Mock private InvestmentTransactionRepository transactionRepository;
    @Mock private AccountRepository accountRepository;
    @Mock private UserRepository userRepository;
    @Mock private InstrumentSearchService instrumentSearchService;
    @Mock private HoldingsSnapshotParser holdingsSnapshotParser;
    @Mock private TradeSettlementClassificationRepository classificationRepository;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private FnoTradeRepository fnoTradeRepository;
    @Mock private FnoTradeService fnoTradeService;

    private BrokerReconciliationService service;
    private final UUID brokerAccountId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new BrokerReconciliationService(
                new ZerodhaTradebookParser(), new ZerodhaTaxPnlParser(), new GrowwOrderHistoryParser(),
                new GrowwCapitalGainsParser(), new ChargeCalculator(), instrumentRepository, aliasRepository,
                holdingRepository, transactionRepository, accountRepository, userRepository,
                instrumentSearchService, holdingsSnapshotParser, classificationRepository, eventPublisher,
                fnoTradeRepository, fnoTradeService);
        Account account = new Account();
        account.setType(AccountType.broker);
        account.setName("Test Broker");
        lenient().when(accountRepository.findByIdAndUser_Id(eq(brokerAccountId), any())).thenReturn(Optional.of(account));
    }

    private static byte[] growwSheet(String[][] rows) throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            XSSFSheet sheet = wb.createSheet("Orders");
            String[] header = {"Stock name", "Symbol", "ISIN", "Type", "Quantity", "Value", "Exchange",
                    "Exchange order id", "Execution date and time", "Order status"};
            XSSFRow h = sheet.createRow(0);
            for (int c = 0; c < header.length; c++) h.createCell(c).setCellValue(header[c]);
            for (int r = 0; r < rows.length; r++) {
                XSSFRow row = sheet.createRow(r + 1);
                for (int c = 0; c < rows[r].length; c++) row.createCell(c).setCellValue(rows[r][c]);
            }
            wb.write(out);
            return out.toByteArray();
        }
    }

    private static String[][] threeScrips() {
        return new String[][]{
                {"Acme Industries", "ACMEIND", "INE000X01011", "BUY", "10", "1000", "NSE", "T-1", "02-02-2024 10:00 AM", "Executed"},
                {"Acme Industries", "ACMEIND", "INE000X01011", "BUY", "5", "600", "NSE", "T-2", "03-02-2024 10:00 AM", "Executed"},
                {"Bravo Works", "BRAVOW", "INE000X01029", "BUY", "4", "400", "BSE", "T-3", "02-02-2024 11:00 AM", "Executed"},
                {"Zeta Growth Fund", "", "INF000X01037", "BUY", "3", "300", "NSE", "T-4", "02-02-2024 12:00 PM", "Executed"},
        };
    }

    @Test
    void previewOnEmptyCatalogSuggestsInstrumentPerScrip() throws Exception {
        ReconcilePreviewResponse res = service.preview(Broker.groww, brokerAccountId,
                List.of(new ByteArrayInputStream(growwSheet(threeScrips()))), List.of());

        assertEquals(4, res.executions().size());
        for (ReconcilePreviewResponse.ReconciledExecutionDto e : res.executions()) {
            assertNull(e.matchedInstrument());
            assertNotNull(e.suggestedInstrument(), "row " + e.externalRef());
        }
        List<ReconcilePreviewResponse.ReconciledExecutionDto> acme = res.executions().stream()
                .filter(e -> "ACMEIND".equals(e.symbol())).toList();
        assertEquals(2, acme.size());
        assertEquals(acme.get(0).suggestedInstrument(), acme.get(1).suggestedInstrument());
        assertEquals(new CreateInstrumentDto(InstrumentType.stock, "Acme Industries", "ACMEIND", "NSE",
                "INE000X01011", null, "ACMEIND.NS"), acme.get(0).suggestedInstrument());

        CreateInstrumentDto bravo = res.executions().stream().filter(e -> "BRAVOW".equals(e.symbol()))
                .findFirst().orElseThrow().suggestedInstrument();
        assertEquals(new CreateInstrumentDto(InstrumentType.stock, "Bravo Works", "BRAVOW", "BSE",
                "INE000X01029", null, "BRAVOW.BO"), bravo);

        CreateInstrumentDto fund = res.executions().stream().filter(e -> "INF000X01037".equals(e.isin()))
                .findFirst().orElseThrow().suggestedInstrument();
        assertEquals(InstrumentType.mutual_fund, fund.type());
        assertEquals("Zeta Growth Fund", fund.name());
        assertEquals("MUTUAL_FUND", fund.exchange());
        assertNull(fund.yahooSymbol());

        assertTrue(res.warnings().stream().noneMatch(w -> "UNRESOLVED_INSTRUMENT".equals(w.type())));
        assertEquals(0, res.summaryStats().matchedInstruments());
        assertEquals(3, res.summaryStats().unresolvedInstruments());
    }

    @Test
    void previewWithCatalogHitHasNoSuggestion() throws Exception {
        Instrument inst = new Instrument();
        inst.setId(UUID.randomUUID());
        inst.setSymbol("ACMEIND");
        inst.setIsin("INE000X01011");
        inst.setName("Acme Industries");
        inst.setType(InstrumentType.stock);
        when(instrumentRepository.findByIsin("INE000X01011")).thenReturn(Optional.of(inst));
        when(transactionRepository.findFilteredTransactions(any(), any(), any(), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        String[][] rows = {threeScrips()[0], threeScrips()[1]};
        ReconcilePreviewResponse res = service.preview(Broker.groww, brokerAccountId,
                List.of(new ByteArrayInputStream(growwSheet(rows))), List.of());

        assertEquals(2, res.executions().size());
        for (ReconcilePreviewResponse.ReconciledExecutionDto e : res.executions()) {
            assertNotNull(e.matchedInstrument());
            assertNull(e.suggestedInstrument());
        }
        assertEquals(1, res.summaryStats().matchedInstruments());
        assertEquals(0, res.summaryStats().unresolvedInstruments());
    }

    private ReconcileCommitRequest.CommitExecutionDto commitRow(int idx, String ref, CreateInstrumentDto dto) {
        return new ReconcileCommitRequest.CommitExecutionDto(idx, LocalDate.of(2024, 2, 2),
                InvestmentTransactionType.buy, SettlementType.delivery, dto.symbol(), dto.isin(), dto.exchange(),
                new BigDecimal("1"), new BigDecimal("100"), null, ref, null, dto, false);
    }

    @Test
    void commitSharesOneCreatedInstrumentAndDerivesYahooSymbol() {
        lenient().when(transactionRepository.findFilteredTransactions(any(), any(), any(), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));
        when(instrumentRepository.save(any(Instrument.class))).thenAnswer(inv -> {
            Instrument i = inv.getArgument(0);
            i.setId(UUID.randomUUID());
            return i;
        });
        CreateInstrumentDto acme = new CreateInstrumentDto(InstrumentType.stock, "Acme Industries", "ACMEIND",
                "NSE", "INE000X01011", null, null);
        CreateInstrumentDto bravo = new CreateInstrumentDto(InstrumentType.stock, "Bravo Works", "BRAVOW",
                "BSE", "INE000X01029", null, null);

        ImportCommitResponse res = service.commit(new ReconcileCommitRequest(Broker.groww, brokerAccountId,
                List.of(commitRow(1, "T-1", acme), commitRow(2, "T-2", acme), commitRow(3, "T-3", acme),
                        commitRow(4, "T-4", bravo)), null, null));

        ArgumentCaptor<Instrument> saved = ArgumentCaptor.forClass(Instrument.class);
        verify(instrumentRepository, times(2)).save(saved.capture());
        assertEquals("ACMEIND.NS", saved.getAllValues().get(0).getYahooSymbol());
        assertEquals("BRAVOW.BO", saved.getAllValues().get(1).getYahooSymbol());
        assertEquals(4, res.committed());
        assertTrue(res.failed().isEmpty());
    }

    @Test
    void commitReusesCatalogInstrumentWithSameIsin() {
        lenient().when(transactionRepository.findFilteredTransactions(any(), any(), any(), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));
        Instrument existing = new Instrument();
        existing.setId(UUID.randomUUID());
        existing.setSymbol("ACMEIND");
        existing.setIsin("INE000X01011");
        existing.setType(InstrumentType.stock);
        when(instrumentRepository.findByIsin("INE000X01011")).thenReturn(Optional.of(existing));
        CreateInstrumentDto acme = new CreateInstrumentDto(InstrumentType.stock, "Acme Industries", "ACMEIND",
                "NSE", "INE000X01011", null, null);

        ImportCommitResponse res = service.commit(new ReconcileCommitRequest(Broker.groww, brokerAccountId,
                List.of(commitRow(1, "T-1", acme), commitRow(2, "T-2", acme)), null, null));

        verify(instrumentRepository, never()).save(any(Instrument.class));
        assertEquals(2, res.committed());
    }

    @Test
    void repeatedUploadContentIsDroppedKeepingOrder() {
        byte[] a = {1, 2, 3};
        byte[] aCopy = {1, 2, 3};
        byte[] b = {4, 5};
        List<byte[]> kept = BrokerReconciliationService.dropRepeatedUploads("tradebookFiles",
                List.of("one.xlsx", "two.xlsx", "three.xlsx"), List.of(a, aCopy, b));
        assertEquals(2, kept.size());
        assertSame(a, kept.get(0));
        assertSame(b, kept.get(1));
    }
}
