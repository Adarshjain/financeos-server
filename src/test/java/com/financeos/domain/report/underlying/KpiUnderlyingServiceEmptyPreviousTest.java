package com.financeos.domain.report.underlying;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.financeos.core.security.UserContext;
import com.financeos.domain.account.cycle.BillingCycleService;
import com.financeos.domain.account.cycle.CycleWindows;
import com.financeos.domain.report.ReportDataService;
import com.financeos.domain.report.ReportDataService.ResolvedDefinition;
import com.financeos.domain.report.ReportDefinitionValidator;
import com.financeos.domain.report.ReportFieldValuesService;
import com.financeos.domain.report.ReportType;
import com.financeos.domain.report.breakdown.RowBreakdownService;
import com.financeos.domain.report.datasource.Aggregation;
import com.financeos.domain.report.datasource.ComputedReportDatasource;
import com.financeos.domain.report.datasource.DatasourceCatalog.FieldDef;
import com.financeos.domain.report.datasource.DatasourceRegistry;
import com.financeos.domain.report.datasource.FieldRole;
import com.financeos.domain.report.datasource.FieldType;
import com.financeos.domain.report.definition.Comparison;
import com.financeos.domain.report.definition.ComparisonDisplay;
import com.financeos.domain.report.definition.FilterClause;
import com.financeos.domain.report.definition.KpiDefinition;
import com.financeos.domain.report.engine.DateRangeResolver;
import com.financeos.domain.report.engine.InMemoryReportExecutor;
import com.financeos.domain.report.engine.KpiData;
import com.financeos.domain.report.engine.KpiPeriodResolver;
import com.financeos.domain.report.engine.KpiReportExecutor;
import com.financeos.domain.report.engine.TableData;
import com.financeos.domain.report.engine.TableReportExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The underlying data's Previous tab of a computed KPI whose previous period has no rows: for
 * every aggregation and either display it lists nothing, and its value is the comparison's
 * previous value (zero for SUM/COUNT, none for AVG/MIN/MAX), the comparison itself being shown.
 */
class KpiUnderlyingServiceEmptyPreviousTest {

    private static final List<ReportType> ALL = List.of(ReportType.KPI, ReportType.CHART, ReportType.TABLE);

    private final UUID userId = UUID.randomUUID();
    private InMemoryReportExecutor inMemory;
    private ReportDataService reportData;
    private KpiUnderlyingService service;

    private final ComputedReportDatasource ds = new ComputedReportDatasource() {
        @Override
        public String name() {
            return "computed";
        }

        @Override
        public String label() {
            return "Computed";
        }

        @Override
        public List<FieldDef> fields() {
            return List.of(
                    new FieldDef("date", "Date", FieldType.DATE, FieldRole.DIMENSION, null, null, null, ALL),
                    new FieldDef("value", "Value", FieldType.NUMBER, FieldRole.MEASURE, List.of(Aggregation.values()),
                            null, null, ALL, "currency"));
        }

        @Override
        public List<Map<String, Object>> rows() {
            Map<String, Object> row = new HashMap<>();
            row.put("id", "a");
            row.put("date", LocalDate.of(2026, 5, 3));
            row.put("value", new BigDecimal("12.5"));
            return List.of(row);
        }
    };

    @BeforeEach
    void setUp() {
        UserContext.setCurrentUserId(userId);
        BillingCycleService cycles = mock(BillingCycleService.class);
        when(cycles.windows(any(), anyInt(), any(), any())).thenReturn(new CycleWindows(Map.of()));
        DateRangeResolver resolver = new DateRangeResolver(4);
        inMemory = new InMemoryReportExecutor(resolver, cycles);
        reportData = mock(ReportDataService.class);
        service = new KpiUnderlyingService(reportData, new ReportDefinitionValidator(mock(DatasourceRegistry.class)),
                new KpiPeriodResolver(resolver, cycles), new KpiReportExecutor(resolver, cycles),
                new TableReportExecutor(resolver), inMemory, new UnderlyingFilterChips(mock(ReportFieldValuesService.class)),
                new RowBreakdownService(mock(DatasourceRegistry.class), List.of()));
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @ParameterizedTest
    @EnumSource(Aggregation.class)
    void thePreviousTabListsNothingAndShowsTheComparisonsPreviousValue(Aggregation aggregation) {
        for (ComparisonDisplay display : ComparisonDisplay.values()) {
            KpiDefinition def = new KpiDefinition("value", aggregation, List.of(may()), new Comparison(true, null, true, display));
            when(reportData.resolveDefinition(ReportType.KPI, ds.name(), null)).thenReturn(new ResolvedDefinition(ds, def));

            KpiData kpi = inMemory.execute(def, ds, Map.of());
            KpiUnderlyingResponse previous = service.adHoc(ReportType.KPI, ds.name(), null, "previous", null, null, null);

            assertNotNull(kpi.comparison(), display.json());
            assertTrue(previous.previousAvailable());
            assertEquals(kpi.comparison().previousValue(), previous.value(), display.json());
            boolean zeroWhenEmpty = aggregation == Aggregation.SUM || aggregation == Aggregation.COUNT;
            assertEquals(zeroWhenEmpty ? BigDecimal.ZERO : null, previous.value());
            assertEquals(0, previous.rowCount());
            assertEquals(List.of(), ((TableData) previous.table()).rows());
            assertEquals(kpi.comparison().previousDateRange().from(), previous.range().from());
            assertEquals(kpi.comparison().previousDateRange().to(), previous.range().to());
        }
    }

    private static FilterClause may() {
        return new FilterClause("date", "between",
                JsonNodeFactory.instance.objectNode().put("from", "2026-05-01").put("to", "2026-05-31"));
    }
}
