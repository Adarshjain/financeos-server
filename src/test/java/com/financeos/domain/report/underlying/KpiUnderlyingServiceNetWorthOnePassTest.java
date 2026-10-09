package com.financeos.domain.report.underlying;

import com.financeos.core.security.UserContext;
import com.financeos.core.time.AppTime;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountService;
import com.financeos.domain.account.AccountType;
import com.financeos.domain.account.cycle.BillingCycleService;
import com.financeos.domain.lending.LendingService;
import com.financeos.domain.loan.LoanService;
import com.financeos.domain.loan.LoanStatus;
import com.financeos.domain.report.ReportDataService;
import com.financeos.domain.report.ReportDataService.ResolvedDefinition;
import com.financeos.domain.report.ReportDefinitionValidator;
import com.financeos.domain.report.ReportFieldValuesService;
import com.financeos.domain.report.ReportType;
import com.financeos.domain.report.breakdown.RowBreakdownService;
import com.financeos.domain.report.datasource.Aggregation;
import com.financeos.domain.report.datasource.DatasourceRegistry;
import com.financeos.domain.report.datasource.impl.NetWorthDatasource;
import com.financeos.domain.report.definition.KpiDefinition;
import com.financeos.domain.report.engine.DateRangeResolver;
import com.financeos.domain.report.engine.InMemoryReportExecutor;
import com.financeos.domain.report.engine.KpiPeriodResolver;
import com.financeos.domain.report.engine.KpiReportExecutor;
import com.financeos.domain.report.engine.TableData;
import com.financeos.domain.report.engine.TableReportExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * One net worth underlying-data request computes the net worth snapshot once: the listed rows,
 * the Assets/Liabilities totals and the Not counted items all come from the same pass over
 * accounts, loans and counterparties (each fetched once per request, and again on the next one).
 */
class KpiUnderlyingServiceNetWorthOnePassTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 8);

    private AccountService accountService;
    private LoanService loanService;
    private LendingService lendingService;
    private NetWorthDatasource netWorth;
    private KpiUnderlyingService service;

    @BeforeEach
    void setUp() {
        AppTime.useClock(Clock.fixed(TODAY.atTime(10, 0).atZone(IST).toInstant(), IST));
        UserContext.setCurrentUserId(UUID.randomUUID());
        accountService = mock(AccountService.class);
        loanService = mock(LoanService.class);
        lendingService = mock(LendingService.class);
        Account savings = account("Savings", "1000");
        Account card = new Account("Card", AccountType.credit_card);
        card.setId(UUID.randomUUID());
        card.setCalculatedBalance(new BigDecimal("-200"));
        Account hidden = account("Hidden", "50");
        hidden.setExcludeFromNetAsset(true);
        when(accountService.getAllAccounts()).thenReturn(List.of(savings, card, hidden));
        when(loanService.getLoans(eq(LoanStatus.active), any())).thenReturn(new PageImpl<>(List.of()));
        when(lendingService.getCounterparties(isNull(), any())).thenReturn(new PageImpl<>(List.of()));
        netWorth = new NetWorthDatasource(accountService, loanService, lendingService);

        BillingCycleService cycles = mock(BillingCycleService.class);
        DateRangeResolver resolver = new DateRangeResolver(4);
        ReportDataService reportData = mock(ReportDataService.class);
        when(reportData.resolveDefinition(ReportType.KPI, "net_worth", null)).thenReturn(new ResolvedDefinition(netWorth,
                new KpiDefinition("signedValue", Aggregation.SUM, List.of(), null)));
        service = new KpiUnderlyingService(reportData, new ReportDefinitionValidator(mock(DatasourceRegistry.class)),
                new KpiPeriodResolver(resolver, cycles), new KpiReportExecutor(resolver, cycles),
                new TableReportExecutor(resolver), new InMemoryReportExecutor(resolver, cycles),
                new UnderlyingFilterChips(mock(ReportFieldValuesService.class)),
                new RowBreakdownService(mock(DatasourceRegistry.class), List.of()));
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
        AppTime.reset();
    }

    @Test
    void aPageOfUnderlyingDataComputesTheSnapshotOnce() {
        KpiUnderlyingResponse response = service.adHoc(ReportType.KPI, "net_worth", null, null, null, null, null);

        verify(accountService, times(1)).getAllAccounts();
        verify(loanService, times(1)).getLoans(eq(LoanStatus.active), any());
        verify(lendingService, times(1)).getCounterparties(isNull(), any());
        assertEquals(new BigDecimal("800"), response.value());
        assertEquals(List.of("Savings", "Card"),
                ((TableData) response.table()).rows().stream().map(r -> r.get("name")).toList());
        assertEquals(List.of(new UnderlyingSummaryLine("Assets", new BigDecimal("1000"), "currency"),
                new UnderlyingSummaryLine("Liabilities", new BigDecimal("200"), "currency")), response.summaryLines());
        assertEquals(List.of("Hidden"), response.notCounted().stream().map(UnderlyingExcludedItem::name).toList());
    }

    @Test
    void theCsvComputesTheSnapshotOnce() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        service.adHocCsv(ReportType.KPI, "net_worth", null, null, null, () -> out);

        verify(accountService, times(1)).getAllAccounts();
        verify(loanService, times(1)).getLoans(eq(LoanStatus.active), any());
        verify(lendingService, times(1)).getCounterparties(isNull(), any());
    }

    @Test
    void eachRequestComputesItsOwnSnapshotSoNothingIsStale() {
        service.adHoc(ReportType.KPI, "net_worth", null, null, null, null, null);
        Account renamed = account("Renamed", "5");
        when(accountService.getAllAccounts()).thenReturn(List.of(renamed));

        KpiUnderlyingResponse second = service.adHoc(ReportType.KPI, "net_worth", null, null, null, null, null);

        verify(accountService, times(2)).getAllAccounts();
        assertEquals(List.of("Renamed"), ((TableData) second.table()).rows().stream().map(r -> r.get("name")).toList());
        assertEquals(List.of(), second.notCounted());
    }

    private static Account account(String name, String balance) {
        Account a = new Account(name, AccountType.bank_account);
        a.setId(UUID.randomUUID());
        a.setCalculatedBalance(new BigDecimal(balance));
        return a;
    }
}
