package com.financeos.domain.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.core.exception.ValidationException;
import com.financeos.core.security.UserContext;
import com.financeos.domain.report.datasource.Aggregation;
import com.financeos.domain.report.datasource.ComputedReportDatasource;
import com.financeos.domain.report.datasource.DatasourceRegistry;
import com.financeos.domain.report.datasource.ReportDatasource;
import com.financeos.domain.report.definition.AggregatedTableDefinition;
import com.financeos.domain.report.definition.ChartDefinition;
import com.financeos.domain.report.definition.DimensionRef;
import com.financeos.domain.report.definition.KpiDefinition;
import com.financeos.domain.report.definition.MeasureRef;
import com.financeos.domain.report.definition.RawTableDefinition;
import com.financeos.domain.report.definition.SortClause;
import com.financeos.domain.report.definition.SortDirection;
import com.financeos.domain.report.definition.TableDefinition;
import com.financeos.domain.report.definition.TableMode;
import com.financeos.domain.report.engine.ChartReportExecutor;
import com.financeos.domain.report.engine.InMemoryReportExecutor;
import com.financeos.domain.report.engine.KpiReportExecutor;
import com.financeos.domain.report.engine.TableReportExecutor;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** The run-time {@code sort} parameter of {@link ReportDataService} report runs. */
class ReportDataServiceRuntimeSortTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final UUID userId = UUID.randomUUID();
    private ReportService reportService;
    private ReportDefinitionValidator validator;
    private KpiReportExecutor kpiExecutor;
    private ChartReportExecutor chartExecutor;
    private TableReportExecutor tableExecutor;
    private InMemoryReportExecutor inMemoryExecutor;
    private ReportDataService service;
    private ReportDatasource sqlDatasource;
    private ComputedReportDatasource computedDatasource;

    @BeforeEach
    void setUp() {
        reportService = mock(ReportService.class);
        validator = mock(ReportDefinitionValidator.class);
        DatasourceRegistry registry = mock(DatasourceRegistry.class);
        kpiExecutor = mock(KpiReportExecutor.class);
        chartExecutor = mock(ChartReportExecutor.class);
        tableExecutor = mock(TableReportExecutor.class);
        inMemoryExecutor = mock(InMemoryReportExecutor.class);
        service = new ReportDataService(reportService, validator, registry, mapper,
                kpiExecutor, chartExecutor, tableExecutor, inMemoryExecutor);
        sqlDatasource = mock(ReportDatasource.class);
        computedDatasource = mock(ComputedReportDatasource.class);
        when(registry.byName("transactions")).thenReturn(sqlDatasource);
        when(registry.byName("positions")).thenReturn(computedDatasource);
        UserContext.setCurrentUserId(userId);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private static final String RAW = "{\"mode\":\"raw\",\"columns\":[\"date\",\"amount\"],\"filters\":[],"
            + "\"sort\":[{\"key\":\"date\",\"direction\":\"desc\"}]}";
    private static final String PIVOT = "{\"mode\":\"aggregated\",\"rows\":[{\"field\":\"broker\"}],\"columns\":[],"
            + "\"measures\":[{\"field\":\"invested\",\"aggregation\":\"sum\"}],\"filters\":[],"
            + "\"sort\":[{\"key\":\"broker\",\"direction\":\"asc\"}]}";

    private JsonNode json(String raw) throws Exception {
        return mapper.readTree(raw);
    }

    private TableDefinition sqlTableRun() {
        ArgumentCaptor<TableDefinition> def = ArgumentCaptor.forClass(TableDefinition.class);
        verify(tableExecutor).execute(def.capture(), eq(sqlDatasource), eq(userId), eq(0), eq(25));
        return def.getValue();
    }

    @Test
    void aRawTableRunsWithTheRuntimeClauseInPlaceOfItsSavedSort() throws Exception {
        service.runDefinition(ReportType.TABLE, "transactions", json(RAW), 0, 25, "amount,ASC");

        RawTableDefinition run = (RawTableDefinition) sqlTableRun();
        SortClause clause = new SortClause("amount", SortDirection.ASC);
        assertEquals(List.of(clause), run.sort());
        assertEquals(List.of("date", "amount"), run.columns());
        assertEquals(TableMode.RAW, run.mode());
        assertEquals(List.of(), run.filters());
        ArgumentCaptor<TableDefinition> checked = ArgumentCaptor.forClass(TableDefinition.class);
        verify(validator).validateRuntimeSort(checked.capture(), eq(clause));
        assertEquals(List.of(new SortClause("date", SortDirection.DESC)), checked.getValue().sort());
    }

    @Test
    void aPivotRunsWithTheRuntimeClauseAndKeepsTheRestOfItsDefinition() throws Exception {
        service.runDefinition(ReportType.TABLE, "positions", json(PIVOT), 1, 10, "invested_sum,desc");

        ArgumentCaptor<TableDefinition> def = ArgumentCaptor.forClass(TableDefinition.class);
        verify(inMemoryExecutor).execute(def.capture(), eq(computedDatasource), isNull(), eq(1), eq(10));
        AggregatedTableDefinition run = (AggregatedTableDefinition) def.getValue();
        assertEquals(List.of(new SortClause("invested_sum", SortDirection.DESC)), run.sort());
        assertEquals(TableMode.AGGREGATED, run.mode());
        assertEquals(List.of(new DimensionRef("broker", null)), run.rows());
        assertEquals(List.of(), run.columns());
        assertEquals(List.of(new MeasureRef("invested", Aggregation.SUM)), run.measures());
        assertEquals(List.of(), run.filters());
    }

    @Test
    void anAbsentOrBlankSortKeepsTheTablesOwnOrder() throws Exception {
        for (String sort : new String[] {null, "", "  "}) {
            service.runDefinition(ReportType.TABLE, "transactions", json(RAW), 0, 25, sort);
        }
        service.runDefinition(ReportType.TABLE, "transactions", json(RAW), 0, 25);

        ArgumentCaptor<TableDefinition> def = ArgumentCaptor.forClass(TableDefinition.class);
        verify(tableExecutor, times(4)).execute(def.capture(), eq(sqlDatasource), eq(userId), eq(0), eq(25));
        for (TableDefinition run : def.getAllValues()) {
            assertEquals(List.of(new SortClause("date", SortDirection.DESC)), run.sort());
        }
        verify(validator, never()).validateRuntimeSort(any(), any());
    }

    @Test
    void kpisAndChartsIgnoreTheSort() throws Exception {
        service.runDefinition(ReportType.KPI, "transactions",
                json("{\"measure\":\"amount\",\"aggregation\":\"sum\",\"filters\":[]}"), null, null, "amount,desc");
        service.runDefinition(ReportType.CHART, "transactions", json("{\"chartType\":\"bar\",\"dimension\":{\"field\":"
                + "\"category\"},\"measure\":{\"field\":\"amount\",\"aggregation\":\"sum\"},\"filters\":[]}"),
                null, null, "category,asc");

        verify(kpiExecutor).execute(any(KpiDefinition.class), eq(sqlDatasource), eq(userId));
        verify(chartExecutor).execute(any(ChartDefinition.class), eq(sqlDatasource), eq(userId));
        verify(validator, never()).validateRuntimeSort(any(), any());
    }

    @Test
    void aMalformedSortIs400BeforeAnythingRuns() {
        ValidationException e = assertThrows(ValidationException.class,
                () -> service.runDefinition(ReportType.TABLE, "transactions", json(RAW), 0, 25, "amount"));
        assertEquals("Invalid sort 'amount': expected one '<column>,asc' or '<column>,desc' clause", e.getMessage());
        verifyNoInteractions(validator, tableExecutor, inMemoryExecutor);
    }

    @Test
    void anUnsortableKeyIs400AndNothingRuns() throws Exception {
        doThrow(new ValidationException("Sort key is not an available column: category"))
                .when(validator).validateRuntimeSort(any(), any());

        assertThrows(ValidationException.class,
                () -> service.runDefinition(ReportType.TABLE, "transactions", json(RAW), 0, 25, "category,asc"));
        verifyNoInteractions(tableExecutor, inMemoryExecutor);
    }

    @Test
    void aSavedReportRunsWithTheRuntimeClause() throws Exception {
        UUID id = UUID.randomUUID();
        Report report = new Report(null, "Spend", ReportType.TABLE, "transactions", RAW);
        when(reportService.get(id)).thenReturn(report);

        service.runSaved(id, 0, 25, "amount,desc");

        assertEquals(List.of(new SortClause("amount", SortDirection.DESC)), sqlTableRun().sort());
        verify(validator).validate(eq("transactions"), any());
    }

    @Test
    void aSavedReportWithoutASortKeepsItsOwnOrder() {
        UUID id = UUID.randomUUID();
        when(reportService.get(id)).thenReturn(new Report(null, "Spend", ReportType.TABLE, "transactions", RAW));

        service.runSaved(id, 0, 25, null);

        assertEquals(List.of(new SortClause("date", SortDirection.DESC)), sqlTableRun().sort());
        verify(validator, never()).validateRuntimeSort(any(), any());
    }

    @Test
    void aSavedReportWithAMalformedSortIs400BeforeItIsLoaded() {
        assertThrows(ValidationException.class, () -> service.runSaved(UUID.randomUUID(), 0, 25, "amount,sideways"));
        verifyNoInteractions(reportService, validator, tableExecutor);
    }
}
