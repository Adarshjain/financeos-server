package com.financeos.domain.report.datasource.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import com.financeos.api.lending.dto.CounterpartyResponse;
import com.financeos.api.loan.dto.LoanResponse;
import com.financeos.core.time.AppTime;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountService;
import com.financeos.domain.account.AccountType;
import com.financeos.domain.lending.LendingService;
import com.financeos.domain.loan.LoanService;
import com.financeos.domain.loan.LoanStatus;
import com.financeos.domain.report.definition.SortClause;
import com.financeos.domain.report.definition.SortDirection;
import com.financeos.domain.report.underlying.UnderlyingExcludedItem;
import com.financeos.domain.report.underlying.UnderlyingSummaryLine;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockMakers;
import org.springframework.data.domain.PageImpl;

/** The KPI underlying-data hooks and extras of {@link NetWorthDatasource}. */
class NetWorthDatasourceUnderlyingTest {

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

    // ------------------------------------------------------------------ hooks

    @Test
    void underlyingRowsShowNameKindAndSideGroupedBySideAssetsFirstLargestFirst() {
        assertEquals(List.of("name", "kind", "side"), datasource.underlyingColumns());
        assertEquals(List.of(new SortClause("side", SortDirection.ASC), new SortClause("value", SortDirection.DESC)),
                datasource.underlyingDefaultSort());
        assertEquals("side", datasource.underlyingGroupField());
    }

    // ------------------------------------------------------------------ summary lines

    @Test
    void summaryTotalsTheValueOfTheListedRowsOnEachSide() {
        List<UnderlyingSummaryLine> lines = datasource.summaryLines(List.of(
                row("asset", new BigDecimal("100000")),
                row("liability", new BigDecimal("20000")),
                row("asset", new BigDecimal("3000.50")),
                row("liability", 1500L)));

        assertEquals(List.of(
                new UnderlyingSummaryLine("Assets", new BigDecimal("103000.50"), "currency"),
                new UnderlyingSummaryLine("Liabilities", new BigDecimal("21500"), "currency")), lines);
    }

    @Test
    void summaryOfNoRowsIsZeroOnBothSides() {
        assertEquals(List.of(
                new UnderlyingSummaryLine("Assets", BigDecimal.ZERO, "currency"),
                new UnderlyingSummaryLine("Liabilities", BigDecimal.ZERO, "currency")), datasource.summaryLines(List.of()));
    }

    @Test
    void summarySkipsRowsWithoutAValueOrAKnownSide() {
        Map<String, Object> noValue = new HashMap<>();
        noValue.put("side", "asset");
        noValue.put("value", null);

        List<UnderlyingSummaryLine> lines = datasource.summaryLines(List.of(noValue, row("other", new BigDecimal("5")),
                row("asset", new BigDecimal("7"))));

        assertEquals(new BigDecimal("7"), lines.get(0).value());
        assertEquals(BigDecimal.ZERO, lines.get(1).value());
    }

    // ------------------------------------------------------------------ not counted

    @Test
    void excludedAndClosedAccountsAreNotCountedWithTheirBalance() {
        Account excluded = account("Hidden", AccountType.bank_account, "999");
        excluded.setExcludeFromNetAsset(true);
        Account closed = account("Old card", AccountType.credit_card, "-12.50");
        closed.setClosedOn(LocalDate.of(2026, 9, 1));
        Account closedToday = account("Closed today", AccountType.bank_account, null);
        closedToday.setClosedOn(TODAY);
        Account excludedAndClosed = account("Both", AccountType.generic, "1");
        excludedAndClosed.setExcludeFromNetAsset(true);
        excludedAndClosed.setClosedOn(TODAY.minusDays(3));
        Account closing = account("Closing", AccountType.bank_account, "5");
        closing.setClosedOn(TODAY.plusDays(1));
        when(accountService.getAllAccounts()).thenReturn(List.of(excluded, closed, closedToday, excludedAndClosed, closing));

        assertEquals(List.of(
                new UnderlyingExcludedItem(excluded.getId().toString(), "Hidden", "bank_account", "excluded",
                        "Excluded from net worth", new BigDecimal("999")),
                new UnderlyingExcludedItem(closed.getId().toString(), "Old card", "credit_card", "closed",
                        "Closed on 01/09/2026", new BigDecimal("-12.50")),
                new UnderlyingExcludedItem(closedToday.getId().toString(), "Closed today", "bank_account", "closed",
                        "Closed on 08/10/2026", null),
                new UnderlyingExcludedItem(excludedAndClosed.getId().toString(), "Both", "generic", "excluded",
                        "Excluded from net worth", new BigDecimal("1"))),
                datasource.notCounted());
        assertEquals(List.of("Closing"), datasource.rows().stream().map(r -> r.get("name")).toList());
    }

    @Test
    void rowsThatFailToComputeAreNotCountedAsErrorsWithoutAValue() {
        UUID accountId = UUID.randomUUID();
        Account brokenAccount = mock(Account.class);
        when(brokenAccount.getId()).thenReturn(accountId);
        when(brokenAccount.getName()).thenReturn("Broken bank");
        when(brokenAccount.getType()).thenReturn(AccountType.bank_account);
        when(brokenAccount.getExcludeFromNetAsset()).thenThrow(new IllegalStateException("boom"));
        Account untypedBroken = mock(Account.class);
        when(untypedBroken.getExcludeFromNetAsset()).thenThrow(new IllegalStateException("boom"));
        when(accountService.getAllAccounts()).thenReturn(List.of(brokenAccount, untypedBroken));

        UUID loanId = UUID.randomUUID();
        LoanResponse brokenLoan = mock(LoanResponse.class, withSettings().mockMaker(MockMakers.INLINE));
        when(brokenLoan.id()).thenReturn(loanId);
        when(brokenLoan.name()).thenReturn("Home");
        when(brokenLoan.outstandingPrincipal()).thenThrow(new IllegalStateException("boom"));
        when(loanService.getLoans(eq(LoanStatus.active), any())).thenReturn(new PageImpl<>(List.of(brokenLoan)));

        UUID cpId = UUID.randomUUID();
        CounterpartyResponse brokenCp = mock(CounterpartyResponse.class, withSettings().mockMaker(MockMakers.INLINE));
        when(brokenCp.id()).thenReturn(cpId);
        when(brokenCp.name()).thenReturn("Ravi");
        when(brokenCp.netPosition()).thenThrow(new IllegalStateException("boom"));
        when(lendingService.getCounterparties(isNull(), any())).thenReturn(new PageImpl<>(List.of(brokenCp)));

        assertEquals(List.of(
                new UnderlyingExcludedItem(accountId.toString(), "Broken bank", "bank_account", "error",
                        "Couldn't be calculated", null),
                new UnderlyingExcludedItem(null, null, "generic", "error", "Couldn't be calculated", null),
                new UnderlyingExcludedItem(loanId.toString(), "Home", "loan", "error", "Couldn't be calculated", null),
                new UnderlyingExcludedItem(cpId.toString(), "Ravi", "lending", "error", "Couldn't be calculated", null)),
                datasource.notCounted());
        assertTrue(datasource.rows().isEmpty());
    }

    @Test
    void countedRowsSettledCounterpartiesAndFailedSectionsAreNotListed() {
        when(accountService.getAllAccounts()).thenReturn(List.of(account("Savings", AccountType.bank_account, "10")));
        when(loanService.getLoans(eq(LoanStatus.active), any())).thenThrow(new IllegalStateException("db down"));
        when(lendingService.getCounterparties(isNull(), any())).thenReturn(new PageImpl<>(List.of(
                new CounterpartyResponse(UUID.randomUUID(), "Settled", null, BigDecimal.ZERO, BigDecimal.ZERO,
                        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 2))));

        assertTrue(datasource.notCounted().isEmpty());
    }

    // ------------------------------------------------------------------ helpers

    private static Map<String, Object> row(String side, Object value) {
        Map<String, Object> row = new HashMap<>();
        row.put("side", side);
        row.put("value", value);
        return row;
    }

    private static Account account(String name, AccountType type, String balance) {
        Account a = new Account(name, type);
        a.setId(UUID.randomUUID());
        a.setCalculatedBalance(balance == null ? null : new BigDecimal(balance));
        return a;
    }
}
