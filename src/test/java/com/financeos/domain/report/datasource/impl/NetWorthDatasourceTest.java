package com.financeos.domain.report.datasource.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import com.financeos.api.lending.dto.CounterpartyResponse;
import com.financeos.api.loan.dto.LoanResponse;
import com.financeos.core.time.AppTime;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountBrokerDetails;
import com.financeos.domain.account.AccountService;
import com.financeos.domain.account.AccountType;
import com.financeos.domain.account.FinancialPosition;
import com.financeos.domain.lending.LendingService;
import com.financeos.domain.loan.LoanService;
import com.financeos.domain.loan.LoanStatus;
import com.financeos.domain.report.ReportType;
import com.financeos.domain.report.datasource.Aggregation;
import com.financeos.domain.report.datasource.DatasourceCatalog.FieldDef;
import com.financeos.domain.report.datasource.FieldRole;
import com.financeos.domain.report.datasource.FieldType;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.mockito.MockMakers;

class NetWorthDatasourceTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 8);

    private AccountService accountService;
    private LoanService loanService;
    private LendingService lendingService;
    private NetWorthDatasource datasource;

    @BeforeEach
    void setUp() {
        AppTime.useClock(Clock.fixed(TODAY.atTime(10, 0).atZone(IST).toInstant(), IST));
        accountService = mock(AccountService.class);
        loanService = mock(LoanService.class);
        lendingService = mock(LendingService.class);
        when(accountService.getAllAccounts()).thenReturn(List.of());
        when(loanService.getLoans(eq(LoanStatus.active), any())).thenReturn(new PageImpl<>(List.of()));
        when(lendingService.getCounterparties(isNull(), any())).thenReturn(new PageImpl<>(List.of()));
        datasource = new NetWorthDatasource(accountService, loanService, lendingService);
    }

    @AfterEach
    void tearDown() {
        AppTime.reset();
    }

    // ------------------------------------------------------------------ catalog

    @Test
    void nameLabelAndCatalog() {
        assertEquals("net_worth", datasource.name());
        assertEquals("Net worth", datasource.label());
        assertEquals(List.of("id", "name", "kind", "side", "value", "signedValue", "asOf"),
                datasource.fields().stream().map(FieldDef::name).toList());
        assertEquals(List.of("bank_account", "credit_card", "broker", "generic", "loan", "lending"),
                datasource.field("kind").values());
        assertEquals(List.of("asset", "liability"), datasource.field("side").values());
        for (String measure : List.of("value", "signedValue")) {
            FieldDef f = datasource.field(measure);
            assertEquals(FieldRole.MEASURE, f.role());
            assertEquals(List.of(Aggregation.SUM), f.aggregations());
            assertEquals(List.of(ReportType.KPI, ReportType.CHART, ReportType.TABLE), f.allowedInReports());
            assertEquals("currency", f.format());
        }
        assertEquals(FieldType.DATE, datasource.field("asOf").type());
        assertFalse(datasource.field("id").canFilter());
        assertTrue(datasource.field("name").canFilter());
    }

    // ------------------------------------------------------------------ accounts

    @Test
    void positiveBankBalanceIsAnAsset() {
        Account bank = account("Savings", AccountType.bank_account, "50000");
        accounts(bank);

        Map<String, Object> row = only();

        assertEquals(bank.getId().toString(), row.get("id"));
        assertEquals("Savings", row.get("name"));
        assertEquals("bank_account", row.get("kind"));
        assertEquals("asset", row.get("side"));
        assertEquals(new BigDecimal("50000"), row.get("value"));
        assertEquals(new BigDecimal("50000"), row.get("signedValue"));
        assertEquals(TODAY, row.get("asOf"));
        assertEquals(List.of("id", "name", "kind", "side", "value", "signedValue", "asOf"), List.copyOf(row.keySet()));
    }

    @Test
    void overdrawnBankAccountFlipsToALiabilityShownPositive() {
        accounts(account("Overdraft", AccountType.bank_account, "-1200"));

        Map<String, Object> row = only();

        assertEquals("liability", row.get("side"));
        assertEquals(new BigDecimal("1200"), row.get("value"));
        assertEquals(new BigDecimal("-1200"), row.get("signedValue"));
    }

    @Test
    void creditCardOwedIsALiability() {
        accounts(account("Card", AccountType.credit_card, "-8000"));

        Map<String, Object> row = only();

        assertEquals("credit_card", row.get("kind"));
        assertEquals("liability", row.get("side"));
        assertEquals(new BigDecimal("8000"), row.get("value"));
        assertEquals(new BigDecimal("-8000"), row.get("signedValue"));
    }

    @Test
    void overpaidCreditCardIsAnAsset() {
        accounts(account("Card", AccountType.credit_card, "300"));

        Map<String, Object> row = only();

        assertEquals("asset", row.get("side"));
        assertEquals(new BigDecimal("300"), row.get("value"));
        assertEquals(new BigDecimal("300"), row.get("signedValue"));
    }

    @Test
    void overpaidCreditCardIsAnAssetEvenWhenMarkedLiability() {
        Account card = account("Card", AccountType.credit_card, "300");
        card.setFinancialPosition(FinancialPosition.liability);
        accounts(card);

        assertEquals("asset", only().get("side"));
    }

    @Test
    void settledCreditCardDefaultsToALiabilityOfZero() {
        accounts(account("Card", AccountType.credit_card, "0"));

        Map<String, Object> row = only();

        assertEquals("liability", row.get("side"));
        assertEquals(0, BigDecimal.ZERO.compareTo((BigDecimal) row.get("value")));
        assertEquals(0, BigDecimal.ZERO.compareTo((BigDecimal) row.get("signedValue")));
    }

    @Test
    void brokerUsesItsCalculatedBalanceWhichAlreadyIncludesCashOnce() {
        Account broker = account("Zerodha", AccountType.broker, "150000"); // holdings 140000 + cash 10000
        broker.setBrokerDetails(new AccountBrokerDetails(broker, "zerodha", "AB1234", new BigDecimal("10000")));
        accounts(broker);

        Map<String, Object> row = only();

        assertEquals("broker", row.get("kind"));
        assertEquals("asset", row.get("side"));
        assertEquals(new BigDecimal("150000"), row.get("value"));
    }

    @Test
    void genericAccountIsAnAssetByDefault() {
        accounts(account("Cash", AccountType.generic, "700"));

        Map<String, Object> row = only();

        assertEquals("generic", row.get("kind"));
        assertEquals("asset", row.get("side"));
        assertEquals(new BigDecimal("700"), row.get("signedValue"));
    }

    @Test
    void negativeGenericBalanceIsALiability() {
        accounts(account("IOU", AccountType.generic, "-40"));

        Map<String, Object> row = only();

        assertEquals("liability", row.get("side"));
        assertEquals(new BigDecimal("40"), row.get("value"));
    }

    @Test
    void financialPositionOverridesTheDefaultSideForAPositiveBalance() {
        Account liabilityGeneric = account("Payable", AccountType.generic, "900");
        liabilityGeneric.setFinancialPosition(FinancialPosition.liability);
        Account assetCard = account("Card", AccountType.credit_card, "0");
        assetCard.setFinancialPosition(FinancialPosition.asset);
        accounts(liabilityGeneric, assetCard);

        List<Map<String, Object>> rows = datasource.rows();

        assertEquals("liability", rows.get(0).get("side"));
        assertEquals(new BigDecimal("900"), rows.get(0).get("value"));
        assertEquals(new BigDecimal("-900"), rows.get(0).get("signedValue"));
        assertEquals("asset", rows.get(1).get("side"));
    }

    @Test
    void negativeBalanceIsALiabilityEvenWhenMarkedAsset() {
        Account bank = account("Bank", AccountType.bank_account, "-50");
        bank.setFinancialPosition(FinancialPosition.asset);
        accounts(bank);

        assertEquals("liability", only().get("side"));
    }

    @Test
    void missingBalanceCountsAsZero() {
        Account bank = account("New", AccountType.bank_account, null);
        accounts(bank);

        Map<String, Object> row = only();

        assertEquals("asset", row.get("side"));
        assertEquals(BigDecimal.ZERO, row.get("value"));
    }

    @Test
    void accountWithoutATypeIsGeneric() {
        accounts(account("Untyped", null, "10"));

        Map<String, Object> row = only();

        assertEquals("generic", row.get("kind"));
        assertEquals("asset", row.get("side"));
    }

    @Test
    void excludedFromNetAssetsIsLeftOut() {
        Account excluded = account("Hidden", AccountType.bank_account, "999");
        excluded.setExcludeFromNetAsset(true);
        Account included = account("Shown", AccountType.bank_account, "1");
        included.setExcludeFromNetAsset(null);
        accounts(excluded, included);

        assertEquals(List.of("Shown"), names(datasource.rows()));
    }

    @Test
    void closedAccountsAreLeftOutButAClosingOneCounts() {
        Account closedToday = account("ClosedToday", AccountType.bank_account, "1");
        closedToday.setClosedOn(TODAY);
        Account closedEarlier = account("ClosedEarlier", AccountType.bank_account, "1");
        closedEarlier.setClosedOn(TODAY.minusMonths(1));
        Account closing = account("Closing", AccountType.bank_account, "1");
        closing.setClosedOn(TODAY.plusDays(1));
        accounts(closedToday, closedEarlier, closing);

        assertEquals(List.of("Closing"), names(datasource.rows()));
    }

    @Test
    void accountWithoutIdHasNullId() {
        Account a = account("NoId", AccountType.bank_account, "5");
        a.setId(null);
        accounts(a);

        assertNull(only().get("id"));
    }

    // ------------------------------------------------------------------ loans

    @Test
    void activeLoansAreLiabilitiesAtOutstandingPrincipal() {
        UUID loanId = UUID.randomUUID();
        loans(loan(loanId, "Home", "2500000"));

        Map<String, Object> row = only();

        assertEquals(loanId.toString(), row.get("id"));
        assertEquals("Home", row.get("name"));
        assertEquals("loan", row.get("kind"));
        assertEquals("liability", row.get("side"));
        assertEquals(new BigDecimal("2500000"), row.get("value"));
        assertEquals(new BigDecimal("-2500000"), row.get("signedValue"));
        assertEquals(TODAY, row.get("asOf"));
        verify(loanService).getLoans(LoanStatus.active, Pageable.unpaged());
    }

    @Test
    void loanOutstandingIsAbsoluteAndMissingIsZero() {
        loans(loan(UUID.randomUUID(), "Negative", "-100"), loan(null, "Missing", null));

        List<Map<String, Object>> rows = datasource.rows();

        assertEquals(new BigDecimal("100"), rows.get(0).get("value"));
        assertEquals(BigDecimal.ZERO, rows.get(1).get("value"));
        assertNull(rows.get(1).get("id"));
    }

    // ------------------------------------------------------------------ lendings

    @Test
    void counterpartyWhoOwesYouIsAnAssetAndOneYouOweIsALiability() {
        UUID owesYou = UUID.randomUUID();
        UUID youOwe = UUID.randomUUID();
        counterparties(cp(owesYou, "Asha", "5000"), cp(youOwe, "Ravi", "-1500"));

        List<Map<String, Object>> rows = datasource.rows();

        assertEquals(2, rows.size());
        assertEquals(owesYou.toString(), rows.get(0).get("id"));
        assertEquals("lending", rows.get(0).get("kind"));
        assertEquals("asset", rows.get(0).get("side"));
        assertEquals(new BigDecimal("5000"), rows.get(0).get("value"));
        assertEquals(new BigDecimal("5000"), rows.get(0).get("signedValue"));
        assertEquals("liability", rows.get(1).get("side"));
        assertEquals(new BigDecimal("1500"), rows.get(1).get("value"));
        assertEquals(new BigDecimal("-1500"), rows.get(1).get("signedValue"));
        verify(lendingService).getCounterparties(null, Pageable.unpaged());
    }

    @Test
    void settledOrUnknownCounterpartiesAreSkipped() {
        counterparties(cp(UUID.randomUUID(), "Zero", "0"), cp(UUID.randomUUID(), "ZeroScaled", "0.00"),
                cp(UUID.randomUUID(), "Null", null), cp(UUID.randomUUID(), "Kept", "1"));

        assertEquals(List.of("Kept"), names(datasource.rows()));
    }

    // ------------------------------------------------------------------ totals / order

    @Test
    void sectionsComeAccountsThenLoansThenLendingsAndSignedValuesSumToNetWorth() {
        accounts(account("Bank", AccountType.bank_account, "100000"), account("Card", AccountType.credit_card, "-20000"));
        loans(loan(UUID.randomUUID(), "Car", "50000"));
        counterparties(cp(UUID.randomUUID(), "Asha", "3000"));

        List<Map<String, Object>> rows = datasource.rows();

        assertEquals(List.of("Bank", "Card", "Car", "Asha"), names(rows));
        BigDecimal net = rows.stream().map(r -> (BigDecimal) r.get("signedValue")).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(new BigDecimal("33000"), net);
    }

    // ------------------------------------------------------------------ failure isolation

    @Test
    void aFailingAccountRowIsSkippedAndTheRestKept() {
        Account broken = mock(Account.class);
        when(broken.getId()).thenReturn(UUID.randomUUID());
        when(broken.getExcludeFromNetAsset()).thenThrow(new IllegalStateException("boom"));
        accounts(account("Before", AccountType.bank_account, "1"), broken, account("After", AccountType.bank_account, "2"));

        assertEquals(List.of("Before", "After"), names(datasource.rows()));
    }

    @Test
    void aFailingAccountRowWithoutIdIsStillSkipped() {
        Account broken = mock(Account.class);
        when(broken.getId()).thenReturn(null);
        when(broken.getExcludeFromNetAsset()).thenThrow(new IllegalStateException("boom"));
        accounts(broken, account("After", AccountType.bank_account, "2"));

        assertEquals(List.of("After"), names(datasource.rows()));
    }

    @Test
    void aFailingLoanRowIsSkippedAndTheRestKept() {
        LoanResponse broken = mock(LoanResponse.class, withSettings().mockMaker(MockMakers.INLINE));
        when(broken.id()).thenReturn(UUID.randomUUID());
        when(broken.outstandingPrincipal()).thenThrow(new IllegalStateException("boom"));
        loans(broken, loan(UUID.randomUUID(), "Good", "10"));

        assertEquals(List.of("Good"), names(datasource.rows()));
    }

    @Test
    void aFailingCounterpartyRowIsSkippedAndTheRestKept() {
        CounterpartyResponse broken = mock(CounterpartyResponse.class, withSettings().mockMaker(MockMakers.INLINE));
        when(broken.id()).thenReturn(UUID.randomUUID());
        when(broken.netPosition()).thenThrow(new IllegalStateException("boom"));
        counterparties(broken, cp(UUID.randomUUID(), "Good", "10"));

        assertEquals(List.of("Good"), names(datasource.rows()));
    }

    @Test
    void aFailingAccountSectionLeavesLoansAndLendings() {
        when(accountService.getAllAccounts()).thenThrow(new IllegalStateException("db down"));
        loans(loan(UUID.randomUUID(), "Loan", "1"));
        counterparties(cp(UUID.randomUUID(), "Person", "1"));

        assertEquals(List.of("Loan", "Person"), names(datasource.rows()));
    }

    @Test
    void aFailingLoanSectionLeavesAccountsAndLendings() {
        accounts(account("Bank", AccountType.bank_account, "1"));
        when(loanService.getLoans(eq(LoanStatus.active), any())).thenThrow(new IllegalStateException("boom"));
        counterparties(cp(UUID.randomUUID(), "Person", "1"));

        assertEquals(List.of("Bank", "Person"), names(datasource.rows()));
    }

    @Test
    void aFailingLendingSectionLeavesAccountsAndLoans() {
        accounts(account("Bank", AccountType.bank_account, "1"));
        loans(loan(UUID.randomUUID(), "Loan", "1"));
        when(lendingService.getCounterparties(isNull(), any())).thenThrow(new IllegalStateException("boom"));

        assertEquals(List.of("Bank", "Loan"), names(datasource.rows()));
    }

    @Test
    void aSectionReturningNullYieldsNoRows() {
        when(accountService.getAllAccounts()).thenReturn(null);
        loans(loan(UUID.randomUUID(), "Loan", "1"));

        assertEquals(List.of("Loan"), names(datasource.rows()));
    }

    @Test
    void everythingEmptyIsAnEmptyList() {
        assertTrue(datasource.rows().isEmpty());
    }

    // ------------------------------------------------------------------ helpers

    private Map<String, Object> only() {
        List<Map<String, Object>> rows = datasource.rows();
        assertEquals(1, rows.size());
        return rows.get(0);
    }

    private static List<Object> names(List<Map<String, Object>> rows) {
        return rows.stream().map(r -> r.get("name")).toList();
    }

    private void accounts(Account... accounts) {
        when(accountService.getAllAccounts()).thenReturn(List.of(accounts));
    }

    private void loans(LoanResponse... loans) {
        when(loanService.getLoans(eq(LoanStatus.active), any())).thenReturn(new PageImpl<>(List.of(loans)));
    }

    private void counterparties(CounterpartyResponse... cps) {
        when(lendingService.getCounterparties(isNull(), any())).thenReturn(new PageImpl<>(List.of(cps)));
    }

    private static Account account(String name, AccountType type, String balance) {
        Account a = new Account(name, type);
        a.setId(UUID.randomUUID());
        a.setCalculatedBalance(balance == null ? null : new BigDecimal(balance));
        return a;
    }

    private static LoanResponse loan(UUID id, String name, String outstanding) {
        return new LoanResponse(id, name, null, null, null, null, null, null, null, null, null, null, null,
                LoanStatus.active, null, null, null, null, null, outstanding == null ? null : new BigDecimal(outstanding),
                null, null, null, null, null, null, null, false, null, null);
    }

    private static CounterpartyResponse cp(UUID id, String name, String net) {
        return new CounterpartyResponse(id, name, null, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                net == null ? null : new BigDecimal(net), 1);
    }
}
