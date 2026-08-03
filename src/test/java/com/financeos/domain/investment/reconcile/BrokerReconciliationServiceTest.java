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
import com.financeos.domain.instrument.Instrument;
import com.financeos.domain.instrument.InstrumentType;
import com.financeos.domain.investment.InvestmentTransaction;
import com.financeos.domain.investment.InvestmentTransactionType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BrokerReconciliationServiceTest {

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

    private BrokerReconciliationService reconciliationService;
    private final UUID brokerAccountId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        ZerodhaTradebookParser tradebookParser = new ZerodhaTradebookParser();
        ZerodhaTaxPnlParser taxPnlParser = new ZerodhaTaxPnlParser();
        GrowwOrderHistoryParser growwOrderHistoryParser = new GrowwOrderHistoryParser();
        GrowwCapitalGainsParser growwCapitalGainsParser = new GrowwCapitalGainsParser();
        ChargeCalculator chargeCalculator = new ChargeCalculator();

        reconciliationService = new BrokerReconciliationService(
                tradebookParser,
                taxPnlParser,
                growwOrderHistoryParser,
                growwCapitalGainsParser,
                chargeCalculator,
                instrumentRepository,
                aliasRepository,
                holdingRepository,
                transactionRepository,
                accountRepository,
                userRepository,
                instrumentSearchService,
                holdingsSnapshotParser,
                classificationRepository,
                eventPublisher
        );

        Account account = new Account();
        account.setType(AccountType.broker);
        account.setName("Zerodha Account");
        when(accountRepository.findById(brokerAccountId)).thenReturn(Optional.of(account));
    }

    @Test
    void testReconcileZerodhaTradebookWithoutTaxPnl() {
        String csvContent = "symbol,isin,trade_date,exchange,segment,series,trade_type,auction,quantity,price,trade_id,order_id,order_execution_time\n" +
                "TATAMOTORS,INE155A01022,2021-02-15,NSE,EQ,EQ,buy,false,10,300.0,101,201,2021-02-15 10:00:00\n" +
                "TATAMOTORS,INE155A01022,2021-10-10,NSE,EQ,EQ,buy,false,5,350.0,102,202,2021-10-10 10:00:00\n";

        InputStream tbStream = new ByteArrayInputStream(csvContent.getBytes(StandardCharsets.UTF_8));

        ReconcilePreviewResponse preview = reconciliationService.preview(
                Broker.zerodha,
                brokerAccountId,
                List.of(tbStream),
                List.of()
        );

        assertNotNull(preview);
        assertEquals(2, preview.summaryStats().totalExecutions());
        assertEquals(2, preview.summaryStats().deliveryExecutions());
        assertEquals(0, preview.summaryStats().intradayExecutions());
        assertEquals(1, preview.derivedHoldings().size());

        ReconcilePreviewResponse.DerivedHoldingDto holding = preview.derivedHoldings().get(0);
        assertEquals("TATAMOTORS", holding.symbol());
        assertEquals(new BigDecimal("15.0000"), holding.quantity());
        // Clean cost basis: (10*300 + 5*350) / 15 = 4750 / 15 = 316.6667
        assertEquals(new BigDecimal("316.6667"), holding.avgCost());
    }

    @Test
    void testDuplicateDetectionWithDifferentTradeId() {
        Instrument inst = new Instrument();
        inst.setId(UUID.randomUUID());
        inst.setSymbol("TATAMOTORS");
        inst.setIsin("INE155A01022");
        inst.setType(InstrumentType.stock);

        when(instrumentRepository.findByIsin("INE155A01022")).thenReturn(Optional.of(inst));

        InvestmentTransaction existingTxn = new InvestmentTransaction();
        existingTxn.setExternalRef("101");
        existingTxn.setTradeDate(LocalDate.parse("2021-02-15"));
        existingTxn.setType(InvestmentTransactionType.buy);
        existingTxn.setQuantity(new BigDecimal("10"));
        existingTxn.setPrice(new BigDecimal("300.0"));

        when(transactionRepository.findFilteredTransactions(eq(brokerAccountId), eq(inst.getId()), eq(null), eq(null), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(existingTxn)));

        // CSV has two trades on same day, same qty, same price, but DIFFERENT trade_ids: 101 and 102
        String csvContent = "symbol,isin,trade_date,exchange,segment,series,trade_type,auction,quantity,price,trade_id,order_id,order_execution_time\n" +
                "TATAMOTORS,INE155A01022,2021-02-15,NSE,EQ,EQ,buy,false,10,300.0,101,201,2021-02-15 10:00:00\n" +
                "TATAMOTORS,INE155A01022,2021-02-15,NSE,EQ,EQ,buy,false,10,300.0,102,202,2021-02-15 10:05:00\n";

        InputStream tbStream = new ByteArrayInputStream(csvContent.getBytes(StandardCharsets.UTF_8));
        ReconcilePreviewResponse preview = reconciliationService.preview(
                Broker.zerodha,
                brokerAccountId,
                List.of(tbStream),
                List.of()
        );

        assertNotNull(preview);
        assertEquals(2, preview.executions().size());

        ReconcilePreviewResponse.ReconciledExecutionDto exec1 = preview.executions().stream().filter(e -> "101".equals(e.externalRef())).findFirst().orElseThrow();
        ReconcilePreviewResponse.ReconciledExecutionDto exec2 = preview.executions().stream().filter(e -> "102".equals(e.externalRef())).findFirst().orElseThrow();

        // exec1 matches trade_id 101 -> duplicate
        assertTrue(exec1.isDuplicate());
        // exec2 has trade_id 102 -> NOT duplicate despite matching date, type, qty, price
        assertFalse(exec2.isDuplicate());
    }

    @Test
    void testDuplicateDetectionFuzzyFallbackWhenRefMissing() {
        Instrument inst = new Instrument();
        inst.setId(UUID.randomUUID());
        inst.setSymbol("TATAMOTORS");
        inst.setIsin("INE155A01022");
        inst.setType(InstrumentType.stock);

        when(instrumentRepository.findByIsin("INE155A01022")).thenReturn(Optional.of(inst));

        InvestmentTransaction existingTxn = new InvestmentTransaction();
        existingTxn.setExternalRef(null); // Ref missing on existing transaction
        existingTxn.setTradeDate(LocalDate.parse("2021-02-15"));
        existingTxn.setType(InvestmentTransactionType.buy);
        existingTxn.setQuantity(new BigDecimal("10"));
        existingTxn.setPrice(new BigDecimal("300.0"));

        when(transactionRepository.findFilteredTransactions(eq(brokerAccountId), eq(inst.getId()), eq(null), eq(null), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(existingTxn)));

        // CSV row without trade_id / order_id
        String csvContent = "symbol,isin,trade_date,exchange,segment,series,trade_type,auction,quantity,price,trade_id,order_id,order_execution_time\n" +
                "TATAMOTORS,INE155A01022,2021-02-15,NSE,EQ,EQ,buy,false,10,300.0,,,2021-02-15 10:00:00\n";

        InputStream tbStream = new ByteArrayInputStream(csvContent.getBytes(StandardCharsets.UTF_8));
        ReconcilePreviewResponse preview = reconciliationService.preview(
                Broker.zerodha,
                brokerAccountId,
                List.of(tbStream),
                List.of()
        );

        assertNotNull(preview);
        assertEquals(1, preview.executions().size());
        // Fuzzy fallback triggers because ref is missing on existing side -> duplicate
        assertTrue(preview.executions().get(0).isDuplicate());
    }
}
