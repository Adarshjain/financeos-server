package com.financeos.domain.investment;

import com.financeos.api.investment.dto.CreateInvestmentTransactionRequest;
import com.financeos.api.investment.dto.ReconcileCommitRequest;
import com.financeos.core.exception.ResourceNotFoundException;
import com.financeos.core.exception.ValidationException;
import com.financeos.core.security.UserContext;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.account.AccountType;
import com.financeos.domain.holding.HoldingRepository;
import com.financeos.domain.instrument.InstrumentAliasRepository;
import com.financeos.domain.instrument.InstrumentRepository;
import com.financeos.domain.instrument.search.InstrumentSearchService;
import com.financeos.domain.instrument.corporateaction.CorporateActionRepository;
import com.financeos.domain.instrument.InstrumentPriceRepository;
import com.financeos.domain.investment.dividend.DividendRepository;
import com.financeos.domain.investment.fno.FnoTradeRepository;
import com.financeos.domain.investment.fno.FnoTradeService;
import com.financeos.domain.investment.imports.ImportService;
import com.financeos.domain.investment.imports.ImportSource;
import com.financeos.domain.investment.reconcile.Broker;
import com.financeos.domain.investment.reconcile.BrokerReconciliationService;
import com.financeos.domain.investment.charges.ChargeCalculator;
import com.financeos.domain.investment.reconcile.HoldingsSnapshotParser;
import com.financeos.domain.investment.reconcile.GrowwCapitalGainsParser;
import com.financeos.domain.investment.reconcile.GrowwOrderHistoryParser;
import com.financeos.domain.investment.reconcile.ZerodhaTaxPnlParser;
import com.financeos.domain.investment.imports.ZerodhaTradebookParser;
import com.financeos.domain.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.context.ApplicationEventPublisher;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Every investment write that names a broker account looks it up as the signed-in user's own: another
 * user's broker account id reads as not found (the same answer as a missing id, so the response never
 * says whether the id exists or what type it is), and nothing is read from or written to it. One of the
 * user's own accounts of the wrong type is still rejected as not a broker account.
 */
class BrokerAccountOwnershipTest {

    private AccountRepository accountRepository;
    private InstrumentRepository instrumentRepository;
    private InvestmentTransactionRepository transactionRepository;
    private FnoTradeService fnoTradeService;

    private InvestmentService investmentService;
    private ImportService importService;
    private BrokerReconciliationService reconciliationService;

    private final UUID me = UUID.randomUUID();
    private final UUID theirAccountId = UUID.randomUUID();
    private Account myBank;

    @BeforeEach
    void setUp() {
        accountRepository = mock(AccountRepository.class);
        instrumentRepository = mock(InstrumentRepository.class);
        transactionRepository = mock(InvestmentTransactionRepository.class);
        HoldingRepository holdingRepository = mock(HoldingRepository.class);
        UserRepository userRepository = mock(UserRepository.class);
        InstrumentAliasRepository aliasRepository = mock(InstrumentAliasRepository.class);
        ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
        FnoTradeRepository fnoTradeRepository = mock(FnoTradeRepository.class);
        fnoTradeService = mock(FnoTradeService.class);

        investmentService = new InvestmentService(transactionRepository, holdingRepository, accountRepository,
                instrumentRepository, mock(InstrumentPriceRepository.class), userRepository,
                mock(CorporateActionRepository.class), mock(DividendRepository.class),
                mock(TradeSettlementClassificationRepository.class), events, fnoTradeRepository, null);
        importService = new ImportService(List.of(), instrumentRepository, aliasRepository, holdingRepository,
                transactionRepository, mock(DividendRepository.class), accountRepository, userRepository,
                mock(InstrumentSearchService.class), events);
        reconciliationService = new BrokerReconciliationService(mock(ZerodhaTradebookParser.class),
                mock(ZerodhaTaxPnlParser.class), mock(GrowwOrderHistoryParser.class), mock(GrowwCapitalGainsParser.class),
                mock(ChargeCalculator.class), instrumentRepository, aliasRepository, holdingRepository,
                transactionRepository, accountRepository, userRepository, mock(InstrumentSearchService.class),
                mock(HoldingsSnapshotParser.class), mock(TradeSettlementClassificationRepository.class), events,
                fnoTradeRepository, fnoTradeService);

        // The repository answers per owner: the other user's account is not mine.
        when(accountRepository.findByIdAndUser_Id(theirAccountId, me)).thenReturn(Optional.empty());
        myBank = new Account();
        myBank.setId(UUID.randomUUID());
        myBank.setType(AccountType.bank_account);
        when(accountRepository.findByIdAndUser_Id(myBank.getId(), me)).thenReturn(Optional.of(myBank));
        UserContext.setCurrentUserId(me);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    // ---------------------------------------------------------------- manual trade

    @Test
    void createTransaction_onAnotherUsersBrokerAccountIsNotFound() {
        assertNotFoundAndUntouched(() -> investmentService.createTransaction(tradeRequest(theirAccountId)));
        verify(instrumentRepository, never()).findById(any());
        verify(transactionRepository, never()).save(any());
    }

    @Test
    void createTransaction_onMyNonBrokerAccountIsRejected() {
        assertThrows(ValidationException.class, () -> investmentService.createTransaction(tradeRequest(myBank.getId())));
        verify(transactionRepository, never()).save(any());
    }

    // ---------------------------------------------------------------- file import

    @Test
    void importPreview_intoAnotherUsersBrokerAccountIsNotFound() {
        assertNotFoundAndUntouched(() -> importService.preview(emptyFile(), ImportSource.zerodha_tradebook, theirAccountId));
    }

    @Test
    void importPreview_intoMyNonBrokerAccountIsRejected() {
        assertThrows(ValidationException.class,
                () -> importService.preview(emptyFile(), ImportSource.zerodha_tradebook, myBank.getId()));
    }

    @Test
    void importCommit_intoAnotherUsersBrokerAccountIsNotFoundAndSavesNothing() {
        assertNotFoundAndUntouched(() -> importService.commit(ImportSource.zerodha_tradebook, theirAccountId, List.of()));
        verify(transactionRepository, never()).save(any());
    }

    @Test
    void importCommit_intoMyNonBrokerAccountIsRejected() {
        assertThrows(ValidationException.class,
                () -> importService.commit(ImportSource.zerodha_tradebook, myBank.getId(), List.of()));
        verify(transactionRepository, never()).save(any());
    }

    // ---------------------------------------------------------------- broker reconciliation

    @Test
    void reconcilePreview_forAnotherUsersBrokerAccountIsNotFound() {
        assertNotFoundAndUntouched(() -> reconciliationService.preview(Broker.zerodha, theirAccountId,
                List.of(), List.of(), null, null, null));
    }

    @Test
    void reconcilePreview_forMyNonBrokerAccountIsRejected() {
        assertThrows(ValidationException.class, () -> reconciliationService.preview(Broker.zerodha, myBank.getId(),
                List.of(), List.of(), null, null, null));
    }

    @Test
    void reconcileCommit_intoAnotherUsersBrokerAccountIsNotFoundAndSavesNothing() {
        assertNotFoundAndUntouched(() -> reconciliationService.commit(reconcileRequest(theirAccountId)));
        verify(transactionRepository, never()).save(any());
        verify(fnoTradeService, never()).importTrades(any(), any());
    }

    @Test
    void reconcileCommit_intoMyNonBrokerAccountIsRejected() {
        assertThrows(ValidationException.class, () -> reconciliationService.commit(reconcileRequest(myBank.getId())));
        verify(transactionRepository, never()).save(any());
    }

    // ---------------------------------------------------------------- helpers

    /** Not found, looked up as the signed-in user's own account, and never fetched by bare id. */
    private void assertNotFoundAndUntouched(Executable call) {
        assertThrows(ResourceNotFoundException.class, call);
        verify(accountRepository).findByIdAndUser_Id(theirAccountId, me);
        verify(accountRepository, never()).findById(any());
    }

    private static CreateInvestmentTransactionRequest tradeRequest(UUID brokerAccountId) {
        return new CreateInvestmentTransactionRequest(brokerAccountId, UUID.randomUUID(), InvestmentTransactionType.buy,
                null, BigDecimal.ONE, BigDecimal.TEN, LocalDate.of(2026, 1, 2), null, null);
    }

    private static ReconcileCommitRequest reconcileRequest(UUID brokerAccountId) {
        return new ReconcileCommitRequest(Broker.zerodha, brokerAccountId, List.of(), List.of(), List.of());
    }

    private static ByteArrayInputStream emptyFile() {
        return new ByteArrayInputStream(new byte[0]);
    }
}
