package com.financeos.domain.investment.reconcile;

import com.financeos.api.investment.dto.ReconcilePreviewResponse;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.account.AccountType;
import com.financeos.domain.holding.HoldingRepository;
import com.financeos.domain.instrument.InstrumentAliasRepository;
import com.financeos.domain.instrument.InstrumentRepository;
import com.financeos.domain.instrument.search.InstrumentSearchService;
import com.financeos.domain.investment.InvestmentTransactionRepository;
import com.financeos.domain.investment.TradeSettlementClassificationRepository;
import com.financeos.domain.investment.charges.ChargeCalculator;
import com.financeos.domain.investment.imports.ZerodhaTradebookParser;
import com.financeos.domain.user.UserRepository;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.financeos.domain.investment.InvestmentTransactionType;
import com.financeos.domain.investment.SettlementType;
import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IntradayClassificationTest {

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

    private BrokerReconciliationService svc;
    private final UUID brokerAccountId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        svc = new BrokerReconciliationService(
                new ZerodhaTradebookParser(),
                new ZerodhaTaxPnlParser(),
                new GrowwOrderHistoryParser(),
                new GrowwCapitalGainsParser(),
                new ChargeCalculator(),
                instrumentRepository, aliasRepository, holdingRepository, transactionRepository,
                accountRepository, userRepository, instrumentSearchService, holdingsSnapshotParser,
                classificationRepository, eventPublisher);

        Account account = new Account();
        account.setType(AccountType.broker);
        account.setName("Zerodha");
        when(accountRepository.findById(brokerAccountId)).thenReturn(Optional.of(account));
        lenient().when(instrumentRepository.findByIsin(any())).thenReturn(Optional.empty());
        lenient().when(instrumentRepository.searchInstruments(any(), any())).thenReturn(List.of());
    }

    /** A tradebook row: symbol,isin,date,type,qty,price,trade_id */
    private static String tb(String... rows) {
        StringBuilder sb = new StringBuilder("symbol,isin,trade_date,exchange,segment,series,trade_type,auction,quantity,price,trade_id,order_id,order_execution_time\n");
        for (String r : rows) sb.append(r).append("\n");
        return sb.toString();
    }
    private static String r(String sym, String isin, String date, String type, String qty, String price, String tradeId) {
        return String.join(",", sym, isin, date, "NSE", "EQ", "EQ", type, "false", qty, price, tradeId, "ord" + tradeId, date + " 10:00:00");
    }

    /** Build a minimal Zerodha Tax P&L workbook with one intraday exit row.
     *  Cells start at column B (index 1), leaving column A blank — mirroring the real
     *  Zerodha "Tradewise Exits" sheet. This is the exact layout that broke the parser
     *  (compacted header index vs absolute row.getCell); keep the leading blank so the
     *  suite guards against a regression. */
    private static final int COL_OFFSET = 1; // leading blank column A, like the real file
    private static InputStream taxPnlIntraday(String sym, String isin, String date, String qty, String buyVal, String sellVal, String profit) {
        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sh = wb.createSheet("Tradewise Exits from 2021-04-01");
            int rn = 0;
            cell(sh, rn++, COL_OFFSET, "Equity - Intraday");
            Row h = sh.createRow(rn++);
            String[] cols = {"Symbol", "ISIN", "Entry Date", "Exit Date", "Quantity", "Buy Value", "Sell Value", "Profit"};
            for (int i = 0; i < cols.length; i++) h.createCell(i + COL_OFFSET).setCellValue(cols[i]);
            Row d = sh.createRow(rn++);
            String[] vals = {sym, isin, date, date, qty, buyVal, sellVal, profit};
            for (int i = 0; i < vals.length; i++) d.createCell(i + COL_OFFSET).setCellValue(vals[i]);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            wb.write(bos);
            return new ByteArrayInputStream(bos.toByteArray());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
    private static void cell(Sheet sh, int rowNum, int colNum, String value) {
        Row row = sh.getRow(rowNum);
        if (row == null) row = sh.createRow(rowNum);
        Cell c = row.createCell(colNum);
        c.setCellValue(value);
    }

    private ReconcilePreviewResponse run(String csv, InputStream... taxpnl) {
        InputStream tbStream = new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8));
        return svc.preview(Broker.zerodha, brokerAccountId, List.of(tbStream), List.of(taxpnl));
    }

    private void dump(String name, ReconcilePreviewResponse p) {
        System.out.println("\n=== " + name + " ===");
        System.out.printf("  stats: total=%d delivery=%d intraday=%d%n",
                p.summaryStats().totalExecutions(), p.summaryStats().deliveryExecutions(), p.summaryStats().intradayExecutions());
        for (ReconcilePreviewResponse.ReconciledExecutionDto e : p.executions()) {
            System.out.printf("  %-10s %-4s qty=%s @ %s -> %s%n",
                    e.symbol(), e.type(), e.quantity().toPlainString(), e.price().toPlainString(), e.settlementType());
        }
        for (ReconcilePreviewResponse.DerivedHoldingDto h : p.derivedHoldings()) {
            System.out.printf("  HOLDING %-10s qty=%s avg=%s%n", h.symbol(), h.quantity().toPlainString(), h.avgCost().toPlainString());
        }
    }

    private long count(ReconcilePreviewResponse p, SettlementType st, InvestmentTransactionType type) {
        return p.executions().stream()
                .filter(e -> e.settlementType() == st && e.type() == type)
                .count();
    }
    private BigDecimal qtyOf(ReconcilePreviewResponse p, SettlementType st, InvestmentTransactionType type) {
        return p.executions().stream()
                .filter(e -> e.settlementType() == st && e.type() == type)
                .map(ReconcilePreviewResponse.ReconciledExecutionDto::quantity)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Test
    void pureIntraday_singleExecution_bothIntraday() {
        ReconcilePreviewResponse p = run(
                tb(r("TATA", "INE001", "2023-05-10", "buy", "10", "100", "1"),
                   r("TATA", "INE001", "2023-05-10", "sell", "10", "110", "2")),
                taxPnlIntraday("TATA", "INE001", "2023-05-10", "10", "1000", "1100", "100"));
        dump("S1 pure intraday single-exec", p);
        assertEquals(0, p.summaryStats().deliveryExecutions());
        assertEquals(2, p.summaryStats().intradayExecutions());
        assertTrue(p.derivedHoldings().isEmpty(), "no open holding after full intraday round trip");
    }

    @Test
    void pureIntraday_splitBuy_allIntraday() {
        ReconcilePreviewResponse p = run(
                tb(r("TATA", "INE001", "2023-05-10", "buy", "6", "100", "1"),
                   r("TATA", "INE001", "2023-05-10", "buy", "4", "100", "2"),
                   r("TATA", "INE001", "2023-05-10", "sell", "10", "110", "3")),
                taxPnlIntraday("TATA", "INE001", "2023-05-10", "10", "1000", "1100", "100"));
        dump("S2 pure intraday split-buy", p);
        assertEquals(0, p.summaryStats().deliveryExecutions());
        assertEquals(3, p.summaryStats().intradayExecutions());
    }

    @Test
    void partialIntraday_alignedBuys_oneIntradayOneDelivery() {
        // bought 20 (10 + 10), sold 10 intraday, kept 10 delivery
        ReconcilePreviewResponse p = run(
                tb(r("TATA", "INE001", "2023-05-10", "buy", "10", "100", "1"),
                   r("TATA", "INE001", "2023-05-10", "buy", "10", "100", "2"),
                   r("TATA", "INE001", "2023-05-10", "sell", "10", "110", "3")),
                taxPnlIntraday("TATA", "INE001", "2023-05-10", "10", "1000", "1100", "100"));
        dump("S3 partial intraday (bought 20, 10 intra)", p);
        // one buy intraday, one buy delivery, the sell intraday
        assertEquals(new BigDecimal("10"), qtyOf(p, SettlementType.intraday, InvestmentTransactionType.buy));
        assertEquals(new BigDecimal("10"), qtyOf(p, SettlementType.delivery, InvestmentTransactionType.buy));
        assertEquals(new BigDecimal("10"), qtyOf(p, SettlementType.intraday, InvestmentTransactionType.sell));
        assertEquals(0, count(p, SettlementType.delivery, InvestmentTransactionType.sell));
        // tags sum exactly: min(intraday buys=10, intraday sells=10) = 10 = classifier qty
        assertEquals(new BigDecimal("10.0000"), p.derivedHoldings().get(0).quantity());
    }

    @Test
    void partialIntraday_straddlingBuy_isSplit() {
        // bought 15 in ONE fill, sold 10 intraday, kept 5 delivery -> the buy must be split
        ReconcilePreviewResponse p = run(
                tb(r("TATA", "INE001", "2023-05-10", "buy", "15", "100", "1"),
                   r("TATA", "INE001", "2023-05-10", "sell", "10", "110", "2")),
                taxPnlIntraday("TATA", "INE001", "2023-05-10", "10", "1000", "1100", "100"));
        dump("S4 partial intraday single big buy (bought 15, 10 intra)", p);
        assertEquals(new BigDecimal("10"), qtyOf(p, SettlementType.intraday, InvestmentTransactionType.buy));
        assertEquals(new BigDecimal("5"), qtyOf(p, SettlementType.delivery, InvestmentTransactionType.buy));
        assertEquals(new BigDecimal("10"), qtyOf(p, SettlementType.intraday, InvestmentTransactionType.sell));
        // split rows carry distinct external refs so commit saves both
        long distinctRefs = p.executions().stream()
                .map(ReconcilePreviewResponse.ReconciledExecutionDto::externalRef)
                .distinct().count();
        assertEquals(p.executions().size(), distinctRefs, "split rows must have unique external refs");
        assertEquals(new BigDecimal("5.0000"), p.derivedHoldings().get(0).quantity());
    }
}
