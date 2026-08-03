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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Diagnostic harness — runs the REAL Zerodha parsers + classifier against real files.
 * Enable with:
 *   ./mvnw -o test -Dtest=RealFileClassificationDiagnostic \
 *       -Dtb=/path/tradebook1.csv,/path/tradebook2.csv \
 *       -Dtax=/path/taxpnl_FY24.xlsx,/path/taxpnl_FY23.xlsx
 * Skips (no failure) when -Dtb / -Dtax are not supplied.
 */
@ExtendWith(MockitoExtension.class)
class RealFileClassificationDiagnostic {

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
    private ZerodhaTaxPnlParser taxPnlParser;
    private final UUID brokerAccountId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        taxPnlParser = new ZerodhaTaxPnlParser();
        svc = new BrokerReconciliationService(
                new ZerodhaTradebookParser(), taxPnlParser,
                new GrowwOrderHistoryParser(), new GrowwCapitalGainsParser(),
                new ChargeCalculator(),
                instrumentRepository, aliasRepository, holdingRepository, transactionRepository,
                accountRepository, userRepository, instrumentSearchService, holdingsSnapshotParser,
                classificationRepository, eventPublisher);

        Account account = new Account();
        account.setType(AccountType.broker);
        account.setName("Zerodha");
        lenient().when(accountRepository.findById(brokerAccountId)).thenReturn(Optional.of(account));
        lenient().when(instrumentRepository.findByIsin(any())).thenReturn(Optional.empty());
        lenient().when(instrumentRepository.searchInstruments(any(), any())).thenReturn(List.of());
    }

    private static List<InputStream> open(String prop) throws Exception {
        String v = System.getProperty(prop);
        if (v == null || v.isBlank()) return List.of();
        List<InputStream> out = new ArrayList<>();
        for (String p : v.split(",")) out.add(new FileInputStream(p.trim()));
        return out;
    }
    private static List<String> paths(String prop) {
        String v = System.getProperty(prop);
        if (v == null || v.isBlank()) return List.of();
        List<String> out = new ArrayList<>();
        for (String p : v.split(",")) out.add(p.trim());
        return out;
    }

    @Test
    void diagnose() throws Exception {
        List<String> tbPaths = paths("tb");
        List<String> taxPaths = paths("tax");
        assumeTrue(!tbPaths.isEmpty() && !taxPaths.isEmpty(),
                "Provide -Dtb=...csv and -Dtax=...xlsx to run this diagnostic");

        // ---- 1. Raw Tax P&L intraday extraction (what the parser sees) ----
        System.out.println("\n################ TAX P&L PARSE ################");
        int totalRows = 0, intradayRows = 0, stcgRows = 0, ltcgRows = 0, buybackRows = 0, nullDate = 0;
        TreeMap<String, java.math.BigDecimal> intradayByKey = new TreeMap<>();
        for (String p : taxPaths) {
            try (InputStream is = new FileInputStream(p)) {
                List<ZerodhaTaxPnlParser.TaxPnlExit> exits = taxPnlParser.parse(is);
                System.out.printf("  %s -> %d equity exit rows%n", p, exits.size());
                for (ZerodhaTaxPnlParser.TaxPnlExit x : exits) {
                    totalRows++;
                    switch (x.bucket() == null ? "?" : x.bucket()) {
                        case "INTRADAY" -> {
                            intradayRows++;
                            if (x.exitDate() == null && x.entryDate() == null) nullDate++;
                            var d = x.exitDate() != null ? x.exitDate() : x.entryDate();
                            String key = (x.isin() != null ? x.isin() : x.symbol()) + " | " + d;
                            intradayByKey.merge(key, x.quantity(), java.math.BigDecimal::add);
                        }
                        case "STCG" -> stcgRows++;
                        case "LTCG" -> ltcgRows++;
                        case "BUYBACK" -> buybackRows++;
                        default -> {}
                    }
                }
            }
        }
        System.out.printf("  totals: intraday=%d stcg=%d ltcg=%d buyback=%d (nullDateIntraday=%d)%n",
                intradayRows, stcgRows, ltcgRows, buybackRows, nullDate);
        System.out.println("  --- intraday (ISIN|date -> qty) ---");
        intradayByKey.forEach((k, q) -> System.out.printf("    %-30s %s%n", k, q.toPlainString()));

        // ---- 2. Full classification via the service ----
        ReconcilePreviewResponse p = svc.preview(Broker.zerodha, brokerAccountId, open("tb"), open("tax"));
        System.out.println("\n################ CLASSIFICATION RESULT ################");
        System.out.printf("  executions(rows)=%d  delivery=%d  intraday=%d  holdings=%d  warnings=%d%n",
                p.summaryStats().totalExecutions(), p.summaryStats().deliveryExecutions(),
                p.summaryStats().intradayExecutions(), p.derivedHoldings().size(), p.warnings().size());

        long intradayRowsClassified = p.executions().stream()
                .filter(e -> e.settlementType().name().equals("intraday")).count();
        System.out.printf("  rows tagged intraday=%d%n", intradayRowsClassified);

        System.out.println("  --- warnings ---");
        p.warnings().stream().limit(40).forEach(w ->
                System.out.printf("    [%s/%s] %s%n", w.type(), w.severity(), w.message()));

        System.out.println("  --- sample intraday-tagged rows (up to 20) ---");
        p.executions().stream()
                .filter(e -> e.settlementType().name().equals("intraday"))
                .limit(20)
                .forEach(e -> System.out.printf("    %-12s %-4s %s x %s on %s  [%s]%n",
                        e.symbol(), e.type(), e.quantity().toPlainString(), e.price().toPlainString(),
                        e.tradeDate(), e.settlementType()));

        if (intradayRows > 0 && intradayRowsClassified == 0) {
            System.out.println("\n  >>> MISMATCH: Tax P&L HAS intraday rows but NONE were applied to executions.");
            System.out.println("  >>> Likely (ISIN,date) key mismatch between tradebook and Tax P&L. Compare keys above.");
        }
    }
}
