package com.financeos.domain.report.breakdown;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.financeos.api.epf.dto.EpfEmployerResponse;
import com.financeos.core.exception.ResourceNotFoundException;
import com.financeos.core.exception.ValidationException;
import com.financeos.core.time.AppTime;
import com.financeos.domain.epf.EpfEmployerStatus;
import com.financeos.domain.epf.EpfService;
import com.financeos.domain.report.datasource.impl.NetWorthDatasource;
import com.financeos.domain.report.definition.SortClause;
import com.financeos.domain.report.definition.SortDirection;
import com.financeos.domain.report.engine.TableData;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The EPF row of the net worth breakdown, over a mocked {@link EpfService}; tenancy is the service's (see EpfNetWorthBreakdownIntegrationTest). */
class NetWorthEpfBreakdownTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 8);

    private EpfService epf;
    private NetWorthEpfBreakdown breakdown;

    @BeforeEach
    void setUp() {
        AppTime.useClock(Clock.fixed(TODAY.atTime(10, 0).atZone(IST).toInstant(), IST));
        epf = mock(EpfService.class);
        breakdown = new NetWorthEpfBreakdown(epf);
    }

    @AfterEach
    void tearDown() {
        AppTime.reset();
    }

    private static EpfEmployerResponse employer(String name, EpfEmployerStatus status, String balance) {
        return new EpfEmployerResponse(UUID.randomUUID(), name, null, null, null, null, null, status, null,
                new BigDecimal(balance), BigDecimal.ZERO, BigDecimal.ZERO, null, null, false);
    }

    private void twoEmployersAndASettledOne() {
        when(epf.getTotalBalanceOrNull()).thenReturn(new BigDecimal("300"));
        when(epf.getEmployers()).thenReturn(List.of(
                employer("Beta", EpfEmployerStatus.CURRENT, "100"),
                employer("Alpha", EpfEmployerStatus.LEFT, "200"),
                employer("Gamma", EpfEmployerStatus.SETTLED, "0")));
    }

    @Test
    void totalsTheDatasourceRowAndStepsPerEmployerWithABalance() {
        twoEmployersAndASettledOne();

        RowBreakdownResponse r = breakdown.breakdown(25).orElseThrow();

        assertEquals("net_worth", r.datasource());
        assertEquals(NetWorthDatasource.EPF_ROW_ID, r.rowId());
        assertEquals("EPF", r.title());
        assertEquals("EPF", r.kindLabel());
        assertEquals(NetWorthDatasource.sideLabel(com.financeos.domain.account.FinancialPosition.asset), r.subtitle());
        assertEquals(0, new BigDecimal("300").compareTo(r.total()));
        assertEquals(List.of("Beta", "Alpha", r.totalLabel()), r.steps().stream().map(BreakdownStep::label).toList());
        assertEquals(List.of("Pension (EPS) is not counted."), r.notes());
        TableData table = (TableData) r.sections().get(0).table();
        assertEquals("employers", r.sections().get(0).key());
        assertEquals(3, table.rows().size());
        assertEquals(List.of("Employer", "Status", "Balance"), table.columns().stream().map(TableData.Column::label).toList());
        assertEquals("Current", table.rows().get(0).get("status"));
        assertEquals("Settled", table.rows().get(2).get("status"));
    }

    @Test
    void noEmployersIsNoRow() {
        when(epf.getTotalBalanceOrNull()).thenReturn(null);
        assertTrue(breakdown.breakdown(25).isEmpty());
        assertTrue(breakdown.section("employers", 0, 25, null).isEmpty());
    }

    @Test
    void sectionPagesAndSorts() {
        twoEmployersAndASettledOne();

        TableData page = (TableData) breakdown.section("employers", 1, 2, null).orElseThrow();
        assertEquals(1, page.rows().size());
        assertEquals("Gamma", page.rows().get(0).get("name"));

        TableData byName = (TableData) breakdown.section("employers", 0, 25, new SortClause("name", SortDirection.ASC))
                .orElseThrow();
        assertEquals(List.of("Alpha", "Beta", "Gamma"), byName.rows().stream().map(m -> m.get("name")).toList());
        TableData byBalance = (TableData) breakdown.section("employers", 0, 25, new SortClause("balance", SortDirection.DESC))
                .orElseThrow();
        assertEquals("Alpha", byBalance.rows().get(0).get("name"));
    }

    @Test
    void unknownSectionAndSortKeyAreErrors() {
        twoEmployersAndASettledOne();
        assertThrows(ResourceNotFoundException.class, () -> breakdown.section("entries", 0, 25, null));
        assertThrows(ValidationException.class,
                () -> breakdown.section("employers", 0, 25, new SortClause("nope", SortDirection.ASC)));
    }

    @Test
    void providerRoutesTheEpfRowIdAheadOfTheUuidParse() {
        NetWorthEpfBreakdown epfKind = mock(NetWorthEpfBreakdown.class);
        when(epfKind.breakdown(25)).thenReturn(Optional.empty());
        NetWorthBreakdownProvider provider = new NetWorthBreakdownProvider(mock(NetWorthAccountBreakdown.class),
                mock(NetWorthLoanBreakdown.class), mock(NetWorthLendingBreakdown.class), epfKind);
        assertThrows(ResourceNotFoundException.class, () -> provider.breakdown("epf", 25));
    }
}
