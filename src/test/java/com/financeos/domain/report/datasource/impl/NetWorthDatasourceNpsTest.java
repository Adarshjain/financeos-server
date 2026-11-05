package com.financeos.domain.report.datasource.impl;

import com.financeos.core.tx.SectionRunner;
import com.financeos.domain.account.AccountService;
import com.financeos.domain.epf.EpfService;
import com.financeos.domain.lending.LendingService;
import com.financeos.domain.loan.LoanService;
import com.financeos.domain.loan.LoanStatus;
import com.financeos.domain.nps.NpsService;
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

/** The user's own NPS holdings are one asset row in the Investments group, next to EPF; no own account means no row. */
class NetWorthDatasourceNpsTest {

    private NpsService npsService;
    private NetWorthDatasource datasource;

    @BeforeEach
    void setUp() {
        AccountService accountService = mock(AccountService.class);
        LoanService loanService = mock(LoanService.class);
        LendingService lendingService = mock(LendingService.class);
        EpfService epfService = mock(EpfService.class);
        npsService = mock(NpsService.class);
        when(accountService.getAllAccounts()).thenReturn(List.of());
        when(loanService.getLoans(eq(LoanStatus.active), any())).thenReturn(new PageImpl<>(List.of()));
        when(lendingService.getCounterparties(isNull(), any())).thenReturn(new PageImpl<>(List.of()));
        when(epfService.getTotalBalanceOrNull()).thenReturn(null);
        datasource = new NetWorthDatasource(accountService, loanService, lendingService, SectionRunner.DIRECT,
                epfService, npsService);
    }

    @Test
    void npsIsOneInvestmentsAssetRow() {
        when(npsService.getSelfValueOrNull()).thenReturn(new BigDecimal("250000.50"));

        List<Map<String, Object>> rows = datasource.rows();

        assertEquals(1, rows.size());
        Map<String, Object> row = rows.get(0);
        assertEquals("nps", row.get("id"));
        assertEquals("NPS", row.get("name"));
        assertEquals("nps", row.get("kind"));
        assertEquals("asset", row.get("side"));
        assertEquals("investments", row.get("group"));
        assertEquals(0, new BigDecimal("250000.50").compareTo((BigDecimal) row.get("value")));
        assertEquals("NPS", NetWorthDatasource.kindLabel("nps"));
        assertTrue(NetWorthDatasource.KIND_VALUES.contains("nps"));
    }

    @Test
    void noOwnAccountMeansNoRow() {
        when(npsService.getSelfValueOrNull()).thenReturn(null);
        assertTrue(datasource.rows().isEmpty());
    }

    @Test
    void aFailingNpsSectionIsSkippedWithoutFailingTheDatasource() {
        when(npsService.getSelfValueOrNull()).thenThrow(new IllegalStateException("boom"));
        assertTrue(datasource.rows().isEmpty());
    }
}
