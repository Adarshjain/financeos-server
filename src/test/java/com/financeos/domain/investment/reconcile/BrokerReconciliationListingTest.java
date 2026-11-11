package com.financeos.domain.investment.reconcile;

import com.financeos.api.investment.dto.ImportCommitRequest.CreateInstrumentDto;
import com.financeos.api.investment.dto.ImportCommitResponse;
import com.financeos.api.investment.dto.ReconcileCommitRequest;
import com.financeos.api.investment.dto.ReconcilePreviewResponse;
import com.financeos.core.security.UserContext;
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
import org.junit.jupiter.api.AfterEach;
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
class BrokerReconciliationListingTest {

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
    private final UUID user = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new BrokerReconciliationService(
                new ZerodhaTradebookParser(), new ZerodhaTaxPnlParser(), new GrowwOrderHistoryParser(),
                new GrowwCapitalGainsParser(), new ChargeCalculator(), instrumentRepository, aliasRepository,
                holdingRepository, transactionRepository, accountRepository, userRepository,
                instrumentSearchService, holdingsSnapshotParser, classificationRepository, eventPublisher,
                fnoTradeRepository, fnoTradeService);
        UserContext.setCurrentUserId(user);
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

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private static Instrument listing(String exchange) {
        Instrument i = new Instrument();
        i.setId(UUID.randomUUID());
        i.setSymbol("ACMEIND");
        i.setExchange(exchange);
        i.setIsin("INE000X01011");
        i.setName("Acme Industries");
        i.setType(InstrumentType.stock);
        return i;
    }

    private List<UUID> previewIds(Instrument nse, Instrument bse, List<UUID> held) throws Exception {
        when(instrumentRepository.findByIsinIn(any())).thenReturn(List.of(nse, bse));
        when(instrumentRepository.findHeldInstrumentIds(eq(user), any())).thenReturn(held);
        when(transactionRepository.findFilteredTransactions(any(), any(), any(), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));
        String[][] rows = {
                {"Acme Industries", "ACMEIND", "INE000X01011", "BUY", "10", "1000", "NSE", "T-1", "02-02-2024 10:00 AM", "Executed"},
                {"Acme Industries", "ACMEIND", "INE000X01011", "BUY", "5", "600", "BSE", "T-2", "03-02-2024 10:00 AM", "Executed"},
        };
        ReconcilePreviewResponse res = service.preview(Broker.groww, brokerAccountId,
                List.of(new ByteArrayInputStream(growwSheet(rows))), List.of());
        return res.executions().stream().map(e -> e.matchedInstrument().id()).toList();
    }

    @Test
    void anNseRowAndABseRowOfOneIsinResolveToOneListingWhenNeitherIsHeld() throws Exception {
        Instrument nse = listing("NSE");
        Instrument bse = listing("BSE");

        List<UUID> ids = previewIds(nse, bse, List.of());

        assertEquals(2, ids.size());
        assertEquals(ids.get(0), ids.get(1), "one company, one instrument");
    }

    @Test
    void bothRowsOfOneIsinGoToTheListingTheUserHolds() throws Exception {
        Instrument nse = listing("NSE");
        Instrument bse = listing("BSE");

        List<UUID> ids = previewIds(nse, bse, List.of(bse.getId()));

        assertEquals(List.of(bse.getId(), bse.getId()), ids);
    }
}
