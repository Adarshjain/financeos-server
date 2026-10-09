package com.financeos.domain.report.breakdown;

import static com.financeos.domain.report.breakdown.BreakdownAssertions.assertReconciles;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.financeos.core.exception.ResourceNotFoundException;
import com.financeos.core.time.AppTime;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountBankDetails;
import com.financeos.domain.account.AccountBrokerDetails;
import com.financeos.domain.account.AccountService;
import com.financeos.domain.account.AccountType;
import com.financeos.domain.account.BalanceMath;
import com.financeos.domain.account.FinancialPosition;
import com.financeos.domain.category.Category;
import com.financeos.domain.holding.Holding;
import com.financeos.domain.holding.HoldingRepository;
import com.financeos.domain.instrument.Instrument;
import com.financeos.domain.instrument.PriceSource;
import com.financeos.domain.investment.HoldingPosition;
import com.financeos.domain.investment.InvestmentService;
import com.financeos.domain.lending.LendingService;
import com.financeos.domain.loan.LoanService;
import com.financeos.domain.loan.LoanStatus;
import com.financeos.domain.report.datasource.impl.NetWorthDatasource;
import com.financeos.domain.report.engine.ReportData;
import com.financeos.domain.report.engine.TableData;
import com.financeos.domain.transaction.Transaction;
import com.financeos.domain.transaction.TransactionRepository;
import com.financeos.domain.transaction.TransactionType;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

/**
 * Account rows of the net worth breakdown. Balances are produced by the real {@link BalanceMath}
 * from the same raw inputs (anchor statement, opening balance, transaction sums) the account
 * services feed it, and each breakdown is checked to reconcile exactly to the value the
 * {@code net_worth} datasource lists for that account.
 */
class NetWorthAccountBreakdownTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 8);
    private static final LocalDate ANCHOR = LocalDate.of(2026, 9, 30);

    private AccountService accountService;
    private TransactionRepository transactionRepository;
    private HoldingRepository holdingRepository;
    private InvestmentService investmentService;
    private NetWorthAccountBreakdown breakdown;

    @BeforeEach
    void setUp() {
        AppTime.useClock(Clock.fixed(TODAY.atTime(10, 0).atZone(IST).toInstant(), IST));
        accountService = mock(AccountService.class);
        transactionRepository = mock(TransactionRepository.class);
        holdingRepository = mock(HoldingRepository.class);
        investmentService = mock(InvestmentService.class);
        breakdown = new NetWorthAccountBreakdown(accountService, transactionRepository, holdingRepository, investmentService);
        when(transactionRepository.findBalanceTransactions(any(), any(), any())).thenReturn(new PageImpl<>(List.of()));
    }

    @AfterEach
    void tearDown() {
        AppTime.reset();
    }

    // ------------------------------------------------------------------ bank accounts

    @Test
    void anchoredBankAccountStartsFromTheStatementClosingAndAddsWhatCameAfter() {
        Account bank = anchored(AccountType.bank_account, "Savings", "50000", movements(2, "10000", 3, "4500", 0), "0");

        RowBreakdownResponse r = breakdown.breakdown(bank.getId(), 25).orElseThrow();

        assertEquals(List.of(
                step("start", "Closing balance on statement ending 30/09/2026", "50000"),
                step("add", "Credits after 30/09/2026 (2)", "10000"),
                step("subtract", "Debits after 30/09/2026 (3)", "4500"),
                step("equals", "Balance", "55500")), r.steps());
        assertEquals("net_worth", r.datasource());
        assertEquals(bank.getId().toString(), r.rowId());
        assertEquals("Savings", r.title());
        assertEquals("Asset", r.subtitle());
        assertEquals("Bank account", r.kindLabel());
        assertEquals(new BigDecimal("55500"), r.total());
        assertEquals("Balance", r.totalLabel());
        assertEquals("currency", r.format());
        assertEquals(TODAY, r.asOf());
        assertEquals(List.of(), r.notes());
        assertEquals(1, r.sections().size());
        BreakdownSectionData section = r.sections().get(0);
        assertEquals("transactions", section.key());
        assertEquals("Transactions after 30/09/2026", section.label());
        assertEquals("transaction", section.rowAction());
        assertNull(section.rowBreakdownDatasource());
        verify(transactionRepository).findBalanceMovements(bank.getId(), ANCHOR);
        verify(transactionRepository).findBalanceTransactions(bank.getId(), ANCHOR, PageRequest.of(0, 25));
        assertMatchesListedRow(bank, r);
    }

    @Test
    void anchoredBankAccountWithAnOpeningBalanceIgnoresItAndNotesTheReconciliationGap() {
        Account bank = bankWithOpening("1000");
        // Opening 1000 + 40000 before the anchor + 5500 after = 46500, against 55500 anchored.
        applyAnchored(bank, "50000", movements(2, "10000", 3, "4500", 0), "40000");

        RowBreakdownResponse r = breakdown.breakdown(bank.getId(), 25).orElseThrow();

        assertEquals(step("start", "Closing balance on statement ending 30/09/2026", "50000"), r.steps().get(0));
        assertEquals(new BigDecimal("55500"), r.total());
        assertEquals(List.of("Opening balance plus all transactions differs from the statement-anchored balance by ₹9,000."),
                r.notes());
        assertReconciles(r);
        assertMatchesListedRow(bank, r);
    }

    @Test
    void unanchoredBankAccountStartsFromItsOpeningBalanceAndCountsEveryTransaction() {
        Account bank = bankWithOpening("2000");
        applyUnanchored(bank, movements(1, "500", 1, "300", 0));

        RowBreakdownResponse r = breakdown.breakdown(bank.getId(), 10).orElseThrow();

        assertEquals(List.of(
                step("start", "Opening balance", "2000"),
                step("add", "Credits (1)", "500"),
                step("subtract", "Debits (1)", "300"),
                step("equals", "Balance", "2200")), r.steps());
        assertEquals("Transactions", r.sections().get(0).label());
        verify(transactionRepository).findBalanceMovements(eq(bank.getId()), isNull());
        verify(transactionRepository).findBalanceTransactions(bank.getId(), null, PageRequest.of(0, 10));
        assertMatchesListedRow(bank, r);
    }

    @Test
    void unanchoredBankAccountWithoutAnOpeningBalanceStartsFromZero() {
        Account bank = account(AccountType.bank_account, "Salary");
        applyUnanchored(bank, movements(1, "800", 0, "0", 0));

        RowBreakdownResponse r = breakdown.breakdown(bank.getId(), 25).orElseThrow();

        assertEquals(List.of(
                step("start", "Opening balance", "0"),
                step("add", "Credits (1)", "800"),
                step("equals", "Balance", "800")), r.steps());
        assertMatchesListedRow(bank, r);
    }

    @Test
    void overdrawnBankAccountIsAListedLiabilityAndTheChainRunsInThatDirection() {
        Account bank = bankWithOpening("100");
        applyUnanchored(bank, movements(0, "0", 1, "600", 0));

        RowBreakdownResponse r = breakdown.breakdown(bank.getId(), 25).orElseThrow();

        assertEquals(List.of(
                step("start", "Opening balance", "-100"),
                step("add", "Debits (1)", "600"),
                step("equals", "Balance", "500")), r.steps());
        assertEquals("Liability", r.subtitle());
        assertEquals(new BigDecimal("500"), r.total());
        assertMatchesListedRow(bank, r);
    }

    // ------------------------------------------------------------------ generic accounts

    @Test
    void anchoredGenericAccountStartsFromTheStatementClosing() {
        Account generic = anchored(AccountType.generic, "Wallet", "700", movements(1, "300", 0, "0", 0), "0");

        RowBreakdownResponse r = breakdown.breakdown(generic.getId(), 25).orElseThrow();

        assertEquals(List.of(
                step("start", "Closing balance on statement ending 30/09/2026", "700"),
                step("add", "Credits after 30/09/2026 (1)", "300"),
                step("equals", "Balance", "1000")), r.steps());
        assertEquals("Wallet/Cash", r.kindLabel());
        assertMatchesListedRow(generic, r);
    }

    @Test
    void unanchoredGenericAccountHasNoStartStep() {
        Account generic = account(AccountType.generic, "Cash");
        applyUnanchored(generic, movements(2, "900", 1, "200", 0));

        RowBreakdownResponse r = breakdown.breakdown(generic.getId(), 25).orElseThrow();

        assertEquals(List.of(
                step("add", "Credits (2)", "900"),
                step("subtract", "Debits (1)", "200"),
                step("equals", "Balance", "700")), r.steps());
        assertMatchesListedRow(generic, r);
    }

    @Test
    void accountWithoutTransactionsIsJustItsBalance() {
        Account generic = account(AccountType.generic, "Empty");
        applyUnanchored(generic, movements(0, "0", 0, "0", 0));

        RowBreakdownResponse r = breakdown.breakdown(generic.getId(), 25).orElseThrow();

        assertEquals(List.of(step("equals", "Balance", "0")), r.steps());
        assertMatchesListedRow(generic, r);
    }

    @Test
    void untypedAccountIsAnAccountKind() {
        Account untyped = account(null, "Untyped");
        applyUnanchored(untyped, movements(1, "10", 0, "0", 0));

        RowBreakdownResponse r = breakdown.breakdown(untyped.getId(), 25).orElseThrow();

        assertEquals("Account", r.kindLabel());
        assertEquals(List.of(step("add", "Credits (1)", "10"), step("equals", "Balance", "10")), r.steps());
        assertMatchesListedRow(untyped, r);
    }

    @Test
    void positiveBalanceMarkedLiabilityStaysALiabilityAndSumsToItsValue() {
        Account payable = account(AccountType.generic, "Payable");
        payable.setFinancialPosition(FinancialPosition.liability);
        applyUnanchored(payable, movements(1, "900", 0, "0", 0));

        RowBreakdownResponse r = breakdown.breakdown(payable.getId(), 25).orElseThrow();

        assertEquals(List.of(step("add", "Credits (1)", "900"), step("equals", "Balance", "900")), r.steps());
        assertEquals("Liability", r.subtitle());
        assertMatchesListedRow(payable, r);
    }

    // ------------------------------------------------------------------ credit cards

    @Test
    void anchoredCardOwedOnTheStatementAddsSpendsAndSubtractsPayments() {
        Account card = anchored(AccountType.credit_card, "Regalia", "8000", movements(1, "3000", 2, "1500", 0), "0");

        RowBreakdownResponse r = breakdown.breakdown(card.getId(), 25).orElseThrow();

        assertEquals(List.of(
                step("start", "Owed on statement ending 30/09/2026", "8000"),
                step("add", "Spends since 30/09/2026 (2)", "1500"),
                step("subtract", "Payments and refunds since 30/09/2026 (1)", "3000"),
                step("equals", "Outstanding", "6500")), r.steps());
        assertEquals("Liability", r.subtitle());
        assertEquals("Credit card", r.kindLabel());
        assertEquals("Outstanding", r.totalLabel());
        assertMatchesListedRow(card, r);
    }

    @Test
    void anchoredCardInCreditOnTheStatement() {
        // Closing −200 is a credit balance; 1000 spent since leaves 800 owed.
        Account card = anchored(AccountType.credit_card, "Card", "-200", movements(0, "0", 1, "1000", 0), "0");

        RowBreakdownResponse r = breakdown.breakdown(card.getId(), 25).orElseThrow();

        assertEquals(List.of(
                step("start", "In credit on statement ending 30/09/2026", "-200"),
                step("add", "Spends since 30/09/2026 (1)", "1000"),
                step("equals", "Outstanding", "800")), r.steps());
        assertMatchesListedRow(card, r);
    }

    @Test
    void overpaidCardIsAnAssetInCredit() {
        Account card = anchored(AccountType.credit_card, "Card", "1000", movements(1, "1500", 0, "0", 0), "0");
        card.setFinancialPosition(FinancialPosition.liability);

        RowBreakdownResponse r = breakdown.breakdown(card.getId(), 25).orElseThrow();

        assertEquals(List.of(
                step("start", "Owed on statement ending 30/09/2026", "-1000"),
                step("add", "Payments and refunds since 30/09/2026 (1)", "1500"),
                step("equals", "In credit", "500")), r.steps());
        assertEquals("Asset", r.subtitle());
        assertEquals("In credit", r.totalLabel());
        assertMatchesListedRow(card, r);
    }

    @Test
    void cardSettledToZeroOnTheStatementIsOwedNothing() {
        Account card = anchored(AccountType.credit_card, "Card", "0", movements(0, "0", 1, "250", 0), "0");

        RowBreakdownResponse r = breakdown.breakdown(card.getId(), 25).orElseThrow();

        assertEquals(step("start", "Owed on statement ending 30/09/2026", "0"), r.steps().get(0));
        assertEquals(new BigDecimal("250"), r.total());
        assertReconciles(r);
        assertMatchesListedRow(card, r);
    }

    @Test
    void unanchoredCardStartsAtZeroWithAllSpendsAndPayments() {
        Account card = account(AccountType.credit_card, "New card");
        applyUnanchored(card, movements(1, "400", 3, "2400", 0));

        RowBreakdownResponse r = breakdown.breakdown(card.getId(), 25).orElseThrow();

        assertEquals(List.of(
                step("start", "Starting balance", "0"),
                step("add", "Spends (3)", "2400"),
                step("subtract", "Payments and refunds (1)", "400"),
                step("equals", "Outstanding", "2000")), r.steps());
        assertEquals("Transactions", r.sections().get(0).label());
        assertMatchesListedRow(card, r);
    }

    @Test
    void excludedTransactionsAmongTheListedOnesAreNoted() {
        Account card = anchored(AccountType.credit_card, "Card", "100", movements(0, "0", 2, "50", 1), "0");

        RowBreakdownResponse r = breakdown.breakdown(card.getId(), 25).orElseThrow();

        assertEquals(List.of("Excluded transactions still count towards balances."), r.notes());
        assertReconciles(r);
    }

    // ------------------------------------------------------------------ brokers

    @Test
    void brokerIsCashPlusTheMarketValueOfOpenHoldings() {
        Account broker = broker("10000", "150000");
        HoldingPosition priced = position(broker, "Infosys", "50", "1800", "90000");
        HoldingPosition atCost = position(broker, "Unlisted", "10", null, "50000");
        HoldingPosition closed = position(broker, "Sold out", "0", "100", null);
        holdings(broker, priced, atCost, closed, null);

        RowBreakdownResponse r = breakdown.breakdown(broker.getId(), 25).orElseThrow();

        assertEquals(List.of(
                step("start", "Cash balance", "10000"),
                step("add", "Holdings at market value (2)", "140000"),
                step("equals", "Balance", "150000")), r.steps());
        assertEquals("Broker", r.kindLabel());
        assertEquals(List.of(), r.notes());
        BreakdownSectionData section = r.sections().get(0);
        assertEquals("holdings", section.key());
        assertEquals("Holdings", section.label());
        assertEquals("breakdown", section.rowAction());
        assertEquals("positions", section.rowBreakdownDatasource());
        TableData table = (TableData) section.table();
        assertEquals(List.of("instrument", "quantity", "price", "priceDate", "value", "valuation"),
                table.columns().stream().map(TableData.Column::key).toList());
        assertEquals(List.of(holdingRow(priced, null), holdingRow(atCost, "At cost (no price)")), table.rows());
        assertEquals(new TableData.Page(0, 25, 2, 1), table.page());
        assertMatchesListedRow(broker, r);
    }

    @Test
    void brokerHoldingsOfEqualValueAreOrderedByInstrumentName() {
        Account broker = broker("0", "200");
        HoldingPosition b = position(broker, "Beta", "1", "100", "100");
        HoldingPosition a = position(broker, "Alpha", "1", "100", "100");
        holdings(broker, b, a);

        TableData table = (TableData) breakdown.breakdown(broker.getId(), 25).orElseThrow().sections().get(0).table();

        assertEquals(List.of("Alpha", "Beta"), table.rows().stream().map(row -> row.get("instrument")).toList());
    }

    @Test
    void brokerWithoutHoldingsOrBrokerDetailsIsZeroCash() {
        Account broker = account(AccountType.broker, "Empty broker");
        broker.setCalculatedBalance(BigDecimal.ZERO);
        holdings(broker);

        RowBreakdownResponse r = breakdown.breakdown(broker.getId(), 25).orElseThrow();

        assertEquals(List.of(step("start", "Cash balance", "0"), step("equals", "Balance", "0")), r.steps());
        verifyNoInteractions(transactionRepository);
        assertMatchesListedRow(broker, r);
    }

    @Test
    void brokerValueTheHoldingsDoNotExplainShowsAsAnExplicitRoundingDifference() {
        Account broker = broker("100", "1100.01");
        holdings(broker, position(broker, "Fund", "1", "1000", "1000"));

        RowBreakdownResponse r = breakdown.breakdown(broker.getId(), 25).orElseThrow();

        assertEquals(step("add", "Rounding difference", "0.01"), r.steps().get(2));
        assertReconciles(r);
        assertMatchesListedRow(broker, r);
    }

    // ------------------------------------------------------------------ not a row

    @Test
    void excludedClosedOrUnknownAccountsHaveNoBreakdown() {
        Account excluded = account(AccountType.bank_account, "Hidden");
        excluded.setExcludeFromNetAsset(true);
        Account closed = account(AccountType.bank_account, "Closed");
        closed.setClosedOn(TODAY);
        UUID unknown = UUID.randomUUID();
        when(accountService.findOwnedAccount(unknown)).thenReturn(Optional.empty());

        assertTrue(breakdown.breakdown(excluded.getId(), 25).isEmpty());
        assertTrue(breakdown.breakdown(closed.getId(), 25).isEmpty());
        assertTrue(breakdown.breakdown(unknown, 25).isEmpty());
        assertTrue(breakdown.section(excluded.getId(), "transactions", 0, 25).isEmpty());
        assertTrue(breakdown.section(unknown, "transactions", 0, 25).isEmpty());
        verifyNoInteractions(transactionRepository);
    }

    @Test
    void accountClosingAfterTodayStillHasABreakdown() {
        Account closing = account(AccountType.generic, "Closing");
        closing.setClosedOn(TODAY.plusDays(1));
        applyUnanchored(closing, movements(1, "5", 0, "0", 0));

        assertTrue(breakdown.breakdown(closing.getId(), 25).isPresent());
    }

    // ------------------------------------------------------------------ sections

    @Test
    void transactionsSectionPagesTheBalanceTransactionsSignedAsTheBalanceCountsThem() {
        Account bank = anchored(AccountType.bank_account, "Savings", "0", movements(1, "100", 1, "40", 1), "0");
        Category food = category("Food");
        Category fuel = category("Fuel");
        Transaction credit = transaction(TransactionType.CREDIT, "100", "Salary", null, false);
        Transaction debit = transaction(TransactionType.DEBIT, "40", null, "UPI/RAW", true);
        debit.setCategories(Set.of(fuel, food));
        when(transactionRepository.findBalanceTransactions(bank.getId(), ANCHOR, PageRequest.of(2, 2)))
                .thenReturn(new PageImpl<>(List.of(credit, debit), PageRequest.of(2, 2), 6));

        TableData table = (TableData) breakdown.section(bank.getId(), "transactions", 2, 2).orElseThrow();

        assertEquals(List.of("date", "description", "category", "amount", "excluded"),
                table.columns().stream().map(TableData.Column::key).toList());
        assertEquals(new TableData.Column("amount", "Amount", "number", "currency"), table.columns().get(3));
        Map<String, Object> creditRow = table.rows().get(0);
        assertEquals(credit.getId().toString(), creditRow.get("id"));
        assertEquals(credit.getDate(), creditRow.get("date"));
        assertEquals("Salary", creditRow.get("description"));
        assertEquals("", creditRow.get("category"));
        assertEquals(new BigDecimal("100"), creditRow.get("amount"));
        assertEquals(false, creditRow.get("excluded"));
        Map<String, Object> debitRow = table.rows().get(1);
        assertEquals("UPI/RAW", debitRow.get("description"));
        assertEquals("Food, Fuel", debitRow.get("category"));
        assertEquals(new BigDecimal("-40"), debitRow.get("amount"));
        assertEquals(true, debitRow.get("excluded"));
        assertEquals(new TableData.Page(2, 2, 6, 3), table.page());
    }

    @Test
    void unanchoredTransactionsSectionListsEveryTransaction() {
        Account generic = account(AccountType.generic, "Cash");
        applyUnanchored(generic, movements(0, "0", 0, "0", 0));

        breakdown.section(generic.getId(), "transactions", 0, 5);

        verify(transactionRepository).findBalanceTransactions(generic.getId(), null, PageRequest.of(0, 5));
    }

    @Test
    void holdingsSectionPagesTheOpenHoldings() {
        Account broker = broker("0", "600");
        HoldingPosition small = position(broker, "Small", "1", "100", "100");
        HoldingPosition big = position(broker, "Big", "1", "300", "300");
        HoldingPosition mid = position(broker, "Mid", "1", "200", "200");
        holdings(broker, small, big, mid);

        TableData table = (TableData) breakdown.section(broker.getId(), "holdings", 1, 2).orElseThrow();

        assertEquals(List.of(holdingRow(small, null)), table.rows());
        assertEquals(new TableData.Page(1, 2, 3, 2), table.page());
    }

    @Test
    void unknownOrMismatchedSectionsAreNotFound() {
        Account bank = account(AccountType.bank_account, "Bank");
        applyUnanchored(bank, movements(0, "0", 0, "0", 0));
        Account broker = broker("0", "0");
        holdings(broker);

        assertThrows(ResourceNotFoundException.class, () -> breakdown.section(bank.getId(), "holdings", 0, 25));
        assertThrows(ResourceNotFoundException.class, () -> breakdown.section(bank.getId(), "nope", 0, 25));
        assertThrows(ResourceNotFoundException.class, () -> breakdown.section(broker.getId(), "transactions", 0, 25));
    }

    // ------------------------------------------------------------------ fixtures

    /** The value the net_worth datasource lists for the account, and the breakdown reconciles to it. */
    private void assertMatchesListedRow(Account account, RowBreakdownResponse r) {
        AccountService listing = mock(AccountService.class);
        when(listing.getAllAccounts()).thenReturn(List.of(account));
        LoanService loans = mock(LoanService.class);
        when(loans.getLoans(eq(LoanStatus.active), any())).thenReturn(new PageImpl<>(List.of()));
        LendingService lendings = mock(LendingService.class);
        when(lendings.getCounterparties(isNull(), any())).thenReturn(new PageImpl<>(List.of()));
        Map<String, Object> row = new NetWorthDatasource(listing, loans, lendings).rows().get(0);

        assertEquals(row.get("value"), r.total());
        assertEquals("asset".equals(row.get("side")) ? "Asset" : "Liability", r.subtitle());
        assertReconciles(r);
    }

    private Account account(AccountType type, String name) {
        Account account = new Account(name, type);
        account.setId(UUID.randomUUID());
        when(accountService.findOwnedAccount(account.getId())).thenReturn(Optional.of(account));
        return account;
    }

    private Account bankWithOpening(String opening) {
        Account bank = account(AccountType.bank_account, "Bank");
        bank.setBankDetails(new AccountBankDetails(bank, new BigDecimal(opening), null));
        return bank;
    }

    private Account anchored(AccountType type, String name, String closing,
                             TransactionRepository.BalanceMovementsProjection after, String beforeAnchorNet) {
        Account account = account(type, name);
        applyAnchored(account, closing, after, beforeAnchorNet);
        return account;
    }

    /** Balance as the account services compute it: anchor closing + movements after it. */
    private void applyAnchored(Account account, String closing, TransactionRepository.BalanceMovementsProjection after,
                               String beforeAnchorNet) {
        BigDecimal post = after.getCreditSum().subtract(after.getDebitSum());
        BalanceMath.apply(account, ANCHOR, new BigDecimal(closing), post.add(new BigDecimal(beforeAnchorNet)), post);
        when(transactionRepository.findBalanceMovements(account.getId(), ANCHOR)).thenReturn(after);
    }

    /** Balance as the account services compute it with no anchor: opening (banks) + every movement. */
    private void applyUnanchored(Account account, TransactionRepository.BalanceMovementsProjection all) {
        BalanceMath.apply(account, null, null, all.getCreditSum().subtract(all.getDebitSum()), null);
        when(transactionRepository.findBalanceMovements(account.getId(), null)).thenReturn(all);
    }

    private static TransactionRepository.BalanceMovementsProjection movements(long creditCount, String credits,
                                                                              long debitCount, String debits,
                                                                              long excluded) {
        TransactionRepository.BalanceMovementsProjection m = mock(TransactionRepository.BalanceMovementsProjection.class);
        when(m.getCreditCount()).thenReturn(creditCount);
        when(m.getCreditSum()).thenReturn(new BigDecimal(credits));
        when(m.getDebitCount()).thenReturn(debitCount);
        when(m.getDebitSum()).thenReturn(new BigDecimal(debits));
        when(m.getExcludedCount()).thenReturn(excluded);
        return m;
    }

    /** A broker whose services-computed balance (market value + cash) is {@code balance}. */
    private Account broker(String cash, String balance) {
        Account broker = account(AccountType.broker, "Zerodha");
        broker.setBrokerDetails(new AccountBrokerDetails(broker, "zerodha", "AB1", new BigDecimal(cash)));
        broker.setCalculatedBalance(new BigDecimal(balance));
        return broker;
    }

    private void holdings(Account broker, HoldingPosition... positions) {
        List<Holding> holdings = new java.util.ArrayList<>();
        for (HoldingPosition p : positions) {
            Holding h = p != null ? p.holding() : new Holding();
            holdings.add(h);
            when(investmentService.calculateHoldingPosition(h)).thenReturn(p);
        }
        when(holdingRepository.findByBrokerAccountId(broker.getId())).thenReturn(holdings);
    }

    private static HoldingPosition position(Account broker, String instrumentName, String qty, String price, String value) {
        Instrument instrument = new Instrument();
        instrument.setName(instrumentName);
        Holding holding = new Holding();
        holding.setId(UUID.randomUUID());
        holding.setBrokerAccount(broker);
        holding.setInstrument(instrument);
        return new HoldingPosition(holding, new BigDecimal(qty), null, null,
                price == null ? null : new BigDecimal(price), price == null ? null : TODAY.minusDays(1),
                price == null ? null : PriceSource.YAHOO, value == null ? null : new BigDecimal(value),
                null, null, null, null, null, null, null, null);
    }

    private static Map<String, Object> holdingRow(HoldingPosition p, String valuation) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", p.holding().getId().toString());
        row.put("instrument", p.holding().getInstrument().getName());
        row.put("quantity", p.openQty());
        row.put("price", p.latestPrice());
        row.put("priceDate", p.priceAsOf());
        row.put("value", p.currentValue());
        row.put("valuation", valuation);
        return row;
    }

    private static Category category(String name) {
        Category c = new Category();
        c.setId(UUID.randomUUID());
        c.setName(name);
        return c;
    }

    private static Transaction transaction(TransactionType type, String amount, String description,
                                           String sourcedDescription, boolean excluded) {
        Transaction t = new Transaction();
        t.setId(UUID.randomUUID());
        t.setType(type);
        t.setAmount(new BigDecimal(amount));
        t.setDate(ANCHOR.plusDays(3));
        t.setDescription(description);
        t.setSourcedDescription(sourcedDescription);
        t.setTransactionExcluded(excluded);
        return t;
    }

    private static BreakdownStep step(String op, String label, String amount) {
        return new BreakdownStep(op, label, null, new BigDecimal(amount), "currency");
    }
}
