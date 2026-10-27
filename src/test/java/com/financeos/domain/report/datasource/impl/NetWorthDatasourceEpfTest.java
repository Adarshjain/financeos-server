package com.financeos.domain.report.datasource.impl;

import com.financeos.core.time.AppTime;
import com.financeos.domain.account.AccountService;
import com.financeos.domain.epf.EpfService;
import com.financeos.domain.lending.LendingService;
import com.financeos.domain.loan.LoanService;
import com.financeos.domain.loan.LoanStatus;
import com.financeos.core.tx.SectionRunner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The EPF balance is one asset row in the Investments group; no employers means no row. */
class NetWorthDatasourceEpfTest {

    private EpfService epfService;
    private NetWorthDatasource datasource;

    @BeforeEach
    void setUp() {
        AccountService accountService = mock(AccountService.class);
        LoanService loanService = mock(LoanService.class);
        LendingService lendingService = mock(LendingService.class);
        epfService = mock(EpfService.class);
        when(accountService.getAllAccounts()).thenReturn(List.of());
        when(loanService.getLoans(eq(LoanStatus.active), any())).thenReturn(new PageImpl<>(List.of()));
        when(lendingService.getCounterparties(isNull(), any())).thenReturn(new PageImpl<>(List.of()));
        datasource = new NetWorthDatasource(accountService, loanService, lendingService, SectionRunner.DIRECT, epfService);
    }

    @AfterEach
    void tearDown() {
        AppTime.reset();
    }

    @Test
    void epfBalanceIsOneInvestmentsAssetRow() {
        when(epfService.getTotalBalanceOrNull()).thenReturn(new BigDecimal("187500"));

        List<Map<String, Object>> rows = datasource.rows();

        assertEquals(1, rows.size());
        Map<String, Object> row = rows.get(0);
        assertEquals("epf", row.get("id"));
        assertEquals("EPF", row.get("name"));
        assertEquals("epf", row.get("kind"));
        assertEquals("asset", row.get("side"));
        assertEquals("investments", row.get("group"));
        assertEquals(0, new BigDecimal("187500").compareTo((BigDecimal) row.get("value")));
        assertEquals("EPF", NetWorthDatasource.kindLabel("epf"));
    }

    @Test
    void noEmployersMeansNoRow() {
        when(epfService.getTotalBalanceOrNull()).thenReturn(null);
        assertTrue(datasource.rows().isEmpty());
    }

    @Test
    void aFailingEpfSectionIsSkippedWithoutFailingTheDatasource() {
        when(epfService.getTotalBalanceOrNull()).thenThrow(new IllegalStateException("boom"));
        assertTrue(datasource.rows().isEmpty());
    }
}
