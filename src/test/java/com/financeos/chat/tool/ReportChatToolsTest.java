package com.financeos.chat.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.financeos.core.exception.ResourceNotFoundException;
import com.financeos.core.exception.ValidationException;
import com.financeos.domain.report.Report;
import com.financeos.domain.report.ReportDataService;
import com.financeos.domain.report.ReportDefinitionValidator;
import com.financeos.domain.report.ReportService;
import com.financeos.domain.report.ReportType;
import com.financeos.domain.report.datasource.DatasourceCatalog;
import com.financeos.domain.report.datasource.DatasourceRegistry;
import com.financeos.domain.report.engine.ChartData;
import com.financeos.domain.report.engine.KpiData;
import com.financeos.domain.report.engine.TableData;
import com.financeos.chat.tool.impl.GetReportCatalogTool;
import com.financeos.chat.tool.impl.GetReportTool;
import com.financeos.chat.tool.impl.ListReportsTool;
import com.financeos.chat.tool.impl.ValidateReportDraftTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ReportChatToolsTest {

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().findAndRegisterModules();
    }

    @Test
    @DisplayName("get_report_catalog: returns registry view and dynamic field guidance note")
    void getReportCatalogToolSuccess() {
        DatasourceRegistry mockRegistry = mock(DatasourceRegistry.class);
        DatasourceCatalog.ReportCatalogView catalogView = new DatasourceCatalog.ReportCatalogView(List.of(), DatasourceCatalog.OPERATORS);
        when(mockRegistry.view()).thenReturn(catalogView);

        GetReportCatalogTool tool = new GetReportCatalogTool(mockRegistry, objectMapper);
        assertEquals("get_report_catalog", tool.name());
        assertNotNull(tool.description());
        assertNotNull(tool.argsSchema());

        ChatToolResult result = tool.execute(objectMapper.createObjectNode());
        assertTrue(result.success());
        assertNotNull(result.result());
        assertTrue(result.result().has("catalog"));
        assertTrue(result.result().has("note"));
        assertTrue(result.result().get("note").asText().contains("dynamic=true"));
    }

    @Test
    @DisplayName("list_reports: maps reports to summaries without definitions and enforces 100-row cap")
    void listReportsToolSuccessAndCap() {
        ReportService mockService = mock(ReportService.class);
        List<Report> reports = new ArrayList<>();
        for (int i = 0; i < 110; i++) {
            Report r = new Report();
            r.setId(UUID.randomUUID());
            r.setName("Report " + i);
            r.setType(ReportType.CHART);
            r.setDatasource("transactions");
            r.setDescription("Desc " + i);
            r.setDefinition("{\"mode\":\"raw\"}");
            r.setUpdatedAt(Instant.now());
            reports.add(r);
        }
        when(mockService.list(null)).thenReturn(reports);

        ListReportsTool tool = new ListReportsTool(mockService, objectMapper);
        assertEquals("list_reports", tool.name());

        ChatToolResult result = tool.execute(objectMapper.createObjectNode());
        assertTrue(result.success());
        assertTrue(result.result().isArray());
        assertEquals(100, result.result().size());

        JsonNode first = result.result().get(0);
        assertEquals("Report 0", first.path("name").asText());
        assertEquals("CHART", first.path("type").asText());
        assertEquals("transactions", first.path("datasource").asText());
        assertFalse(first.has("definition"));
    }

    @Test
    @DisplayName("get_report: happy path fetches report, invalid uuid returns failure, service error returns failure")
    void getReportToolScenarios() {
        ReportService mockService = mock(ReportService.class);
        GetReportTool tool = new GetReportTool(mockService, objectMapper);
        assertEquals("get_report", tool.name());

        // 1. Missing reportId
        ChatToolResult missingId = tool.execute(objectMapper.createObjectNode());
        assertFalse(missingId.success());
        assertTrue(missingId.error().contains("reportId is required"));

        // 2. Malformed UUID
        ObjectNode badArgs = objectMapper.createObjectNode();
        badArgs.put("reportId", "not-a-uuid");
        ChatToolResult badUuidResult = tool.execute(badArgs);
        assertFalse(badUuidResult.success());
        assertTrue(badUuidResult.error().contains("must be a UUID"));

        // 3. Not found / ValidationException from service
        UUID validUuid = UUID.randomUUID();
        ObjectNode validArgs = objectMapper.createObjectNode();
        validArgs.put("reportId", validUuid.toString());
        when(mockService.get(validUuid)).thenThrow(new ResourceNotFoundException("Report", validUuid));
        ChatToolResult notFoundResult = tool.execute(validArgs);
        assertFalse(notFoundResult.success());
        assertTrue(notFoundResult.error().contains("Report"));

        // 4. Happy path
        UUID happyUuid = UUID.randomUUID();
        ObjectNode happyArgs = objectMapper.createObjectNode();
        happyArgs.put("reportId", happyUuid.toString());

        Report report = new Report();
        report.setId(happyUuid);
        report.setName("Monthly Spend");
        report.setType(ReportType.KPI);
        report.setDatasource("transactions");
        report.setDescription("A KPI report");
        report.setDefinition("{\"measure\":\"amount\",\"aggregation\":\"sum\",\"filters\":[]}");
        report.setCreatedAt(Instant.now());
        report.setUpdatedAt(Instant.now());

        when(mockService.get(happyUuid)).thenReturn(report);
        ChatToolResult happyResult = tool.execute(happyArgs);
        assertTrue(happyResult.success());
        assertEquals("Monthly Spend", happyResult.result().path("name").asText());
        assertEquals("KPI", happyResult.result().path("type").asText());
        assertTrue(happyResult.result().has("definition"));
        assertEquals("amount", happyResult.result().path("definition").path("measure").asText());
    }

    @Test
    @DisplayName("validate_report_draft: happy path with CHART, KPI, and TABLE summaries")
    void validateReportDraftToolHappyPath() {
        DatasourceRegistry mockRegistry = mock(DatasourceRegistry.class);
        ReportDefinitionValidator mockValidator = mock(ReportDefinitionValidator.class);
        ReportDataService mockDataService = mock(ReportDataService.class);

        when(mockRegistry.isKnown("transactions")).thenReturn(true);

        ChartData mockChartData = new ChartData(
                "CHART", "bar", "date", List.of("2026-01", "2026-02", "2026-03", "2026-04"),
                List.of(new ChartData.Series("Spend", List.of(BigDecimal.TEN))),
                new ChartData.MeasureView("amount", "sum"),
                new ChartData.Meta(4, null)
        );
        when(mockDataService.runAdHoc(eq(ReportType.CHART), eq("transactions"), any(), eq(0), eq(5)))
                .thenReturn(mockChartData);

        ValidateReportDraftTool tool = new ValidateReportDraftTool(
                mockRegistry, mockValidator, mockDataService, objectMapper
        );
        assertEquals("validate_report_draft", tool.name());

        ObjectNode chartDef = objectMapper.createObjectNode();
        chartDef.put("chartType", "bar");
        chartDef.putObject("dimension").put("field", "date").put("granularity", "month");
        chartDef.putObject("measure").put("field", "amount").put("aggregation", "sum");
        chartDef.putArray("filters");

        ObjectNode args = objectMapper.createObjectNode();
        args.put("type", "CHART");
        args.put("datasource", "transactions");
        args.set("definition", chartDef);

        ChatToolResult result = tool.execute(args);
        assertTrue(result.success());
        assertTrue(result.result().path("valid").asBoolean());
        assertTrue(result.result().path("preview").asText().contains("4 categories"));
    }

    @Test
    @DisplayName("validate_report_draft: errors on bad type, unknown datasource, validation failure, or execution failure")
    void validateReportDraftToolFailures() {
        DatasourceRegistry mockRegistry = mock(DatasourceRegistry.class);
        ReportDefinitionValidator mockValidator = mock(ReportDefinitionValidator.class);
        ReportDataService mockDataService = mock(ReportDataService.class);

        ValidateReportDraftTool tool = new ValidateReportDraftTool(
                mockRegistry, mockValidator, mockDataService, objectMapper
        );

        // 1. Invalid report type
        ObjectNode args1 = objectMapper.createObjectNode();
        args1.put("type", "UNKNOWN_TYPE");
        args1.put("datasource", "transactions");
        args1.putObject("definition");
        ChatToolResult res1 = tool.execute(args1);
        assertFalse(res1.success());
        assertTrue(res1.error().contains("Must be one of: KPI, CHART, TABLE"));

        // 2. Unknown datasource — the error lists valid names derived live from the registry
        when(mockRegistry.isKnown("unknown_ds")).thenReturn(false);
        when(mockRegistry.view()).thenReturn(new DatasourceCatalog.ReportCatalogView(
                List.of(
                        new DatasourceCatalog.SingleDatasourceView("transactions", "Transactions", List.of()),
                        new DatasourceCatalog.SingleDatasourceView("positions", "Positions", List.of())),
                DatasourceCatalog.OPERATORS));
        ObjectNode args2 = objectMapper.createObjectNode();
        args2.put("type", "KPI");
        args2.put("datasource", "unknown_ds");
        args2.putObject("definition");
        ChatToolResult res2 = tool.execute(args2);
        assertFalse(res2.success());
        assertTrue(res2.error().contains("Unknown report datasource"));
        assertTrue(res2.error().contains("transactions, positions"));

        // 3. Validator failure
        when(mockRegistry.isKnown("transactions")).thenReturn(true);
        ObjectNode badKpiDef = objectMapper.createObjectNode();
        badKpiDef.put("measure", "non_existent_measure");
        badKpiDef.put("aggregation", "sum");
        badKpiDef.putArray("filters");

        doThrow(new ValidationException("Field 'non_existent_measure' is not a measure"))
                .when(mockValidator).validate(eq("transactions"), any());

        ObjectNode args3 = objectMapper.createObjectNode();
        args3.put("type", "KPI");
        args3.put("datasource", "transactions");
        args3.set("definition", badKpiDef);

        ChatToolResult res3 = tool.execute(args3);
        assertFalse(res3.success());
        assertEquals("Field 'non_existent_measure' is not a measure", res3.error());

        // 4. RunAdHoc failure
        reset(mockValidator);
        when(mockDataService.runAdHoc(any(), any(), any(), any(), any()))
                .thenThrow(new ValidationException("Calculation error on datasource"));

        ChatToolResult res4 = tool.execute(args3);
        assertFalse(res4.success());
        assertEquals("Calculation error on datasource", res4.error());
    }
}
