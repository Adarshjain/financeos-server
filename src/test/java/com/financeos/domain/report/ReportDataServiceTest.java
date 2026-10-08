package com.financeos.domain.report;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.core.exception.ValidationException;
import com.financeos.core.security.UserContext;
import com.financeos.domain.report.datasource.ComputedReportDatasource;
import com.financeos.domain.report.datasource.DatasourceRegistry;
import com.financeos.domain.report.datasource.ReportDatasource;
import com.financeos.domain.report.definition.ChartDefinition;
import com.financeos.domain.report.definition.KpiDefinition;
import com.financeos.domain.report.definition.RawTableDefinition;
import com.financeos.domain.report.definition.ReportDefinition;
import com.financeos.domain.report.definition.TableDefinition;
import com.financeos.domain.report.engine.ChartData;
import com.financeos.domain.report.engine.ChartReportExecutor;
import com.financeos.domain.report.engine.InMemoryReportExecutor;
import com.financeos.domain.report.engine.KpiData;
import com.financeos.domain.report.engine.KpiReportExecutor;
import com.financeos.domain.report.engine.ReportData;
import com.financeos.domain.report.engine.TableReportExecutor;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** {@link ReportDataService#runDefinition} (shared by ad-hoc reports and built-in widgets). */
class ReportDataServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final UUID userId = UUID.randomUUID();
    private ReportDefinitionValidator validator;
    private DatasourceRegistry registry;
    private KpiReportExecutor kpiExecutor;
    private ChartReportExecutor chartExecutor;
    private TableReportExecutor tableExecutor;
    private InMemoryReportExecutor inMemoryExecutor;
    private ReportDataService service;

    private ReportDatasource sqlDatasource;
    private ComputedReportDatasource computedDatasource;

    @BeforeEach
    void setUp() {
        validator = mock(ReportDefinitionValidator.class);
        registry = mock(DatasourceRegistry.class);
        kpiExecutor = mock(KpiReportExecutor.class);
        chartExecutor = mock(ChartReportExecutor.class);
        tableExecutor = mock(TableReportExecutor.class);
        inMemoryExecutor = mock(InMemoryReportExecutor.class);
        service = new ReportDataService(mock(ReportService.class), validator, registry, mapper,
                kpiExecutor, chartExecutor, tableExecutor, inMemoryExecutor);
        sqlDatasource = mock(ReportDatasource.class);
        computedDatasource = mock(ComputedReportDatasource.class);
        when(registry.byName("transactions")).thenReturn(sqlDatasource);
        when(registry.byName("net_worth")).thenReturn(computedDatasource);
        when(registry.byName("obligations")).thenReturn(computedDatasource);
        UserContext.setCurrentUserId(userId);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private static KpiData kpiData() {
        return new KpiData("KPI", java.math.BigDecimal.TEN, "amount", "sum", "currency", null, null);
    }

    private static ChartData chartData() {
        return new ChartData("CHART", "bar", "category", java.util.List.of(), java.util.List.of(), null, null);
    }

    private JsonNode kpi() throws Exception {
        return mapper.readTree("{\"measure\":\"amount\",\"aggregation\":\"sum\",\"filters\":[]}");
    }

    private JsonNode chart() throws Exception {
        return mapper.readTree("{\"chartType\":\"bar\",\"dimension\":{\"field\":\"category\"},"
                + "\"measure\":{\"field\":\"spend\",\"aggregation\":\"sum\"},\"filters\":[]}");
    }

    private JsonNode rawTable() throws Exception {
        return mapper.readTree("{\"mode\":\"raw\",\"columns\":[\"dueDate\",\"title\"],\"filters\":[]}");
    }

    @Test
    void nullTypeIs400BeforeAnythingRuns() {
        assertThrows(ValidationException.class, () -> service.runDefinition(null, "transactions", kpi(), null, null));
        verifyNoInteractions(validator, registry, kpiExecutor, chartExecutor, tableExecutor, inMemoryExecutor);
    }

    @Test
    void tableWithoutAModeIsRejectedBeforeValidation() {
        assertThrows(IllegalArgumentException.class, () -> service.runDefinition(ReportType.TABLE, "obligations",
                mapper.readTree("{\"columns\":[\"title\"]}"), null, null));
        verifyNoInteractions(validator, inMemoryExecutor, tableExecutor);
    }

    @Test
    void validationFailureStopsTheRun() throws Exception {
        doThrow(new ValidationException("Unknown field")).when(validator).validate(anyString(), any());

        assertThrows(ValidationException.class, () -> service.runDefinition(ReportType.KPI, "transactions", kpi(), null, null));
        verifyNoInteractions(kpiExecutor, chartExecutor, tableExecutor, inMemoryExecutor);
    }

    @Test
    void definitionIsValidatedAgainstTheNamedDatasource() throws Exception {
        service.runDefinition(ReportType.KPI, "transactions", kpi(), null, null);

        ArgumentCaptor<ReportDefinition> def = ArgumentCaptor.forClass(ReportDefinition.class);
        verify(validator).validate(eq("transactions"), def.capture());
        assertSame(KpiDefinition.class, def.getValue().getClass());
    }

    @Test
    void unknownDatasourceIs400() throws Exception {
        when(registry.byName("nope")).thenThrow(new ValidationException("Unknown report datasource: nope"));
        assertThrows(ValidationException.class, () -> service.runDefinition(ReportType.KPI, "nope", kpi(), null, null));
        verifyNoInteractions(kpiExecutor, inMemoryExecutor);
    }

    @Test
    void sqlKpiRunsOnTheKpiExecutorForTheCurrentUser() throws Exception {
        KpiData data = kpiData();
        when(kpiExecutor.execute(any(KpiDefinition.class), eq(sqlDatasource), eq(userId))).thenReturn(data);
        assertSame(data, service.runDefinition(ReportType.KPI, "transactions", kpi(), null, null));
    }

    @Test
    void sqlChartRunsOnTheChartExecutorForTheCurrentUser() throws Exception {
        ChartData data = chartData();
        when(chartExecutor.execute(any(ChartDefinition.class), eq(sqlDatasource), eq(userId))).thenReturn(data);
        assertSame(data, service.runDefinition(ReportType.CHART, "transactions", chart(), null, null));
    }

    @Test
    void sqlTableRunsOnTheTableExecutorWithPaging() throws Exception {
        ReportData data = mock(ReportData.class);
        when(tableExecutor.execute(any(TableDefinition.class), eq(sqlDatasource), eq(userId), eq(3), eq(50))).thenReturn(data);
        assertSame(data, service.runDefinition(ReportType.TABLE, "transactions", rawTable(), 3, 50));
    }

    @Test
    void computedKpiRunsInMemory() throws Exception {
        KpiData data = kpiData();
        when(inMemoryExecutor.execute(any(KpiDefinition.class), eq(computedDatasource), isNull())).thenReturn(data);
        assertSame(data, service.runDefinition(ReportType.KPI, "net_worth", kpi(), null, null));
        verifyNoInteractions(kpiExecutor);
    }

    @Test
    void computedChartRunsInMemory() throws Exception {
        ChartData data = chartData();
        when(inMemoryExecutor.execute(any(ChartDefinition.class), eq(computedDatasource), isNull())).thenReturn(data);
        assertSame(data, service.runDefinition(ReportType.CHART, "net_worth", chart(), null, null));
        verifyNoInteractions(chartExecutor);
    }

    @Test
    void computedTableRunsInMemoryWithPaging() throws Exception {
        ReportData data = mock(ReportData.class);
        when(inMemoryExecutor.execute(any(TableDefinition.class), eq(computedDatasource), isNull(), eq(1), eq(10)))
                .thenReturn(data);
        assertSame(data, service.runDefinition(ReportType.TABLE, "obligations", rawTable(), 1, 10));
        verifyNoInteractions(tableExecutor);
    }

    @Test
    void runAdHocStillValidatesAndDispatchesTheSameWay() throws Exception {
        ReportData data = mock(ReportData.class);
        when(inMemoryExecutor.execute(any(TableDefinition.class), eq(computedDatasource), isNull(), eq(0), eq(20)))
                .thenReturn(data);

        assertSame(data, service.runAdHoc(ReportType.TABLE, "obligations", rawTable(), 0, 20));

        ArgumentCaptor<ReportDefinition> def = ArgumentCaptor.forClass(ReportDefinition.class);
        verify(validator).validate(eq("obligations"), def.capture());
        assertSame(RawTableDefinition.class, def.getValue().getClass());
        assertThrows(ValidationException.class, () -> service.runAdHoc(null, "obligations", rawTable(), null, null));
    }
}
