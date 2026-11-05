package com.financeos.domain.report.breakdown;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.financeos.api.nps.dto.NpsSummaryResponse;
import com.financeos.core.exception.ResourceNotFoundException;
import com.financeos.core.exception.ValidationException;
import com.financeos.core.time.AppTime;
import com.financeos.domain.nps.NpsAccountStatus;
import com.financeos.domain.nps.NpsCra;
import com.financeos.domain.nps.NpsRelation;
import com.financeos.domain.nps.NpsSector;
import com.financeos.domain.nps.NpsService;
import com.financeos.domain.nps.NpsTier;
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

/** The NPS row of the net worth breakdown, over a mocked {@link NpsService}. */
class NetWorthNpsBreakdownTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 8);

    private NpsService nps;
    private NetWorthNpsBreakdown breakdown;

    @BeforeEach
    void setUp() {
        AppTime.useClock(Clock.fixed(TODAY.atTime(10, 0).atZone(IST).toInstant(), IST));
        nps = mock(NpsService.class);
        breakdown = new NetWorthNpsBreakdown(nps);
    }

    @AfterEach
    void tearDown() {
        AppTime.reset();
    }

    private static NpsSummaryResponse.Tier tier(NpsTier t, String value, String invested) {
        BigDecimal v = new BigDecimal(value);
        BigDecimal i = new BigDecimal(invested);
        return new NpsSummaryResponse.Tier(t, v, i, v.subtract(i), List.of());
    }

    private static NpsSummaryResponse.Account account(String holder, NpsRelation relation,
                                                      List<NpsSummaryResponse.Tier> tiers) {
        return new NpsSummaryResponse.Account(UUID.randomUUID(), "XXXX XXXX 1234", holder, relation,
                NpsSector.ALL_CITIZEN, NpsCra.PROTEAN, null, true, null, NpsAccountStatus.ACTIVE, false, tiers, null, null, null);
    }

    private void ownAccountAndAChild() {
        when(nps.getSelfValueOrNull()).thenReturn(new BigDecimal("300"));
        when(nps.getSummary()).thenReturn(new NpsSummaryResponse(null, List.of(
                account("Me", NpsRelation.SELF, List.of(tier(NpsTier.I, "200", "150"), tier(NpsTier.II, "100", "90"))),
                account("Kid", NpsRelation.CHILD, List.of(tier(NpsTier.I, "999", "900")))),
                null, null, null, null, null));
    }

    @Test
    void totalsTheRowAndStepsPerOwnAccountAndTierLeavingTheChildOut() {
        ownAccountAndAChild();

        RowBreakdownResponse r = breakdown.breakdown(25).orElseThrow();

        assertEquals(NetWorthDatasource.NPS_ROW_ID, r.rowId());
        assertEquals("NPS", r.title());
        assertEquals(0, new BigDecimal("300").compareTo(r.total()));
        assertEquals(List.of("Me Tier I", "Me Tier II", r.totalLabel()),
                r.steps().stream().map(BreakdownStep::label).toList());
        TableData table = (TableData) r.sections().get(0).table();
        assertEquals("accounts", r.sections().get(0).key());
        assertEquals(2, table.rows().size());
        assertEquals(List.of("Account", "Tier", "Value", "Invested"),
                table.columns().stream().map(TableData.Column::label).toList());
        assertEquals(new BigDecimal("150"), table.rows().get(0).get("invested"));
    }

    @Test
    void noOwnAccountIsNoRow() {
        when(nps.getSelfValueOrNull()).thenReturn(null);
        assertTrue(breakdown.breakdown(25).isEmpty());
        assertTrue(breakdown.section("accounts", 0, 25, null).isEmpty());
    }

    @Test
    void sectionSortsAndRejectsUnknownKeys() {
        ownAccountAndAChild();
        TableData byValue = (TableData) breakdown.section("accounts", 0, 25, new SortClause("value", SortDirection.ASC))
                .orElseThrow();
        assertEquals("Tier II", byValue.rows().get(0).get("tier"));
        assertThrows(ResourceNotFoundException.class, () -> breakdown.section("entries", 0, 25, null));
        assertThrows(ValidationException.class,
                () -> breakdown.section("accounts", 0, 25, new SortClause("nope", SortDirection.ASC)));
    }

    @Test
    void providerRoutesTheNpsRowIdAheadOfTheUuidParse() {
        NetWorthNpsBreakdown npsKind = mock(NetWorthNpsBreakdown.class);
        when(npsKind.breakdown(25)).thenReturn(Optional.empty());
        NetWorthBreakdownProvider provider = new NetWorthBreakdownProvider(mock(NetWorthAccountBreakdown.class),
                mock(NetWorthLoanBreakdown.class), mock(NetWorthLendingBreakdown.class),
                mock(NetWorthEpfBreakdown.class), npsKind);
        assertThrows(ResourceNotFoundException.class, () -> provider.breakdown("nps", 25));
    }
}
