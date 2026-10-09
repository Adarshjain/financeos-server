package com.financeos.domain.report.datasource.impl;

import com.financeos.core.time.AppTime;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountService;
import com.financeos.domain.account.AccountType;
import com.financeos.domain.lending.LendingService;
import com.financeos.domain.loan.LoanService;
import com.financeos.domain.loan.LoanStatus;
import com.financeos.domain.report.underlying.UnderlyingExtras;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;

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

/** {@link NetWorthDatasource#underlyingSnapshot()}: the rows and the left-out items from one pass. */
class NetWorthDatasourceSnapshotTest {

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
        when(loanService.getLoans(eq(LoanStatus.active), any())).thenReturn(new PageImpl<>(List.of()));
        when(lendingService.getCounterparties(isNull(), any())).thenReturn(new PageImpl<>(List.of()));
        datasource = new NetWorthDatasource(accountService, loanService, lendingService);
    }

    @AfterEach
    void tearDown() {
        AppTime.reset();
    }

    @Test
    void theSnapshotHoldsTheSameRowsAndLeftOutItemsAsAskingSeparatelyFromOnePass() {
        Account counted = account("Savings", "1000");
        Account excluded = account("Hidden", "50");
        excluded.setExcludeFromNetAsset(true);
        when(accountService.getAllAccounts()).thenReturn(List.of(counted, excluded));

        UnderlyingExtras.Snapshot snapshot = datasource.underlyingSnapshot();

        verify(accountService, times(1)).getAllAccounts();
        verify(loanService, times(1)).getLoans(eq(LoanStatus.active), any());
        verify(lendingService, times(1)).getCounterparties(isNull(), any());
        assertEquals(datasource.rows(), snapshot.rows());
        assertEquals(datasource.notCounted(), snapshot.notCounted());
        assertEquals(List.of("Savings"), snapshot.rows().stream().map(r -> r.get("name")).toList());
        assertEquals(List.of("Hidden"), snapshot.notCounted().stream().map(i -> i.name()).toList());
    }

    private static Account account(String name, String balance) {
        Account a = new Account(name, AccountType.bank_account);
        a.setId(UUID.randomUUID());
        a.setCalculatedBalance(new BigDecimal(balance));
        return a;
    }
}
