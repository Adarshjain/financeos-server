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

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
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
}
