package com.financeos.chat.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.financeos.chat.tool.ChatTool;
import com.financeos.chat.tool.ChatToolResult;
import com.financeos.core.exception.ValidationException;
import com.financeos.domain.report.ReportDataService;
import com.financeos.domain.report.ReportDefinitionValidator;
import com.financeos.domain.report.ReportType;
import com.financeos.domain.report.datasource.DatasourceRegistry;
import com.financeos.domain.report.definition.ReportDefinition;
import com.financeos.domain.report.definition.ReportDefinitions;
import com.financeos.domain.report.engine.ChartData;
import com.financeos.domain.report.engine.KpiData;
import com.financeos.domain.report.engine.PivotTableData;
import com.financeos.domain.report.engine.ReportData;
import com.financeos.domain.report.engine.TableData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

@Component
public class ValidateReportDraftTool implements ChatTool {

    private static final Logger log = LoggerFactory.getLogger(ValidateReportDraftTool.class);

    private final DatasourceRegistry datasourceRegistry;
    private final ReportDefinitionValidator reportDefinitionValidator;
    private final ReportDataService reportDataService;
    private final ObjectMapper objectMapper;

    public ValidateReportDraftTool(DatasourceRegistry datasourceRegistry,
                                   ReportDefinitionValidator reportDefinitionValidator,
                                   ReportDataService reportDataService,
                                   ObjectMapper objectMapper) {
        this.datasourceRegistry = datasourceRegistry;
        this.reportDefinitionValidator = reportDefinitionValidator;
        this.reportDataService = reportDataService;
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return "validate_report_draft";
    }

    @Override
    public String description() {
        return "Validate a draft report definition against the catalog and execute it as a preview. ALWAYS call this before emitting a reportDraft block; fix any returned error and re-validate.";
    }

    @Override
    public JsonNode argsSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");

        ObjectNode typeProp = props.putObject("type");
        typeProp.put("type", "string");
        ArrayNode typeEnum = typeProp.putArray("enum");
        Arrays.stream(ReportType.values()).map(Enum::name).forEach(typeEnum::add);
        typeProp.put("description", "Report type: KPI, CHART, or TABLE");

        ObjectNode dsProp = props.putObject("datasource");
        dsProp.put("type", "string");
        dsProp.put("description", "Datasource name from get_report_catalog (e.g. transactions, positions, etc.)");

        ObjectNode defProp = props.putObject("definition");
        defProp.put("type", "object");
        defProp.put("description", "Complete report definition JSON object");

        ArrayNode req = schema.putArray("required");
        req.add("type").add("datasource").add("definition");
        return schema;
    }

    @Override
    public ChatToolResult execute(JsonNode args) {
        try {
            if (args == null || !args.hasNonNull("type") || !args.hasNonNull("datasource") || !args.hasNonNull("definition")) {
                return ChatToolResult.failure(name(), "type, datasource, and definition are required");
            }

            String typeStr = args.get("type").asText().trim();
            ReportType reportType;
            try {
                reportType = ReportType.valueOf(typeStr.toUpperCase());
            } catch (Exception e) {
                return ChatToolResult.failure(name(), "Invalid report type: '" + typeStr + "'. Must be one of: KPI, CHART, TABLE");
            }

            String datasource = args.get("datasource").asText().trim();
            if (!datasourceRegistry.isKnown(datasource)) {
                String validNames = datasourceRegistry.view().datasources().stream()
                        .map(ds -> ds.name())
                        .collect(java.util.stream.Collectors.joining(", "));
                return ChatToolResult.failure(name(), "Unknown report datasource: '" + datasource + "'. Valid datasources are: " + validNames);
            }

            JsonNode definitionNode = args.get("definition");
            if (!definitionNode.isObject()) {
                return ChatToolResult.failure(name(), "definition must be a JSON object");
            }

            ReportDefinition parsed;
            try {
                parsed = ReportDefinitions.parse(reportType, definitionNode, objectMapper);
                reportDefinitionValidator.validate(datasource, parsed);
            } catch (ValidationException | IllegalArgumentException e) {
                return ChatToolResult.failure(name(), e.getMessage());
            }

            ReportData reportData;
            try {
                reportData = reportDataService.runAdHoc(reportType, datasource, definitionNode, 0, 5);
            } catch (ValidationException | IllegalArgumentException e) {
                return ChatToolResult.failure(name(), e.getMessage());
            } catch (Exception e) {
                log.warn("Error running ad-hoc preview in validate_report_draft", e);
                return ChatToolResult.failure(name(), "Preview execution failed: " + (e.getMessage() != null ? e.getMessage() : "internal error"));
            }

            String previewSummary = summarizePreview(reportData);
            ObjectNode resultNode = objectMapper.createObjectNode();
            resultNode.put("valid", true);
            resultNode.put("preview", previewSummary);

            return ChatToolResult.success(name(), resultNode);
        } catch (ValidationException | IllegalArgumentException e) {
            return ChatToolResult.failure(name(), e.getMessage());
        } catch (Exception e) {
            log.warn("Unexpected error in validate_report_draft", e);
            return ChatToolResult.failure(name(), "internal error");
        }
    }

    private String summarizePreview(ReportData data) {
        if (data == null) {
            return "No data returned";
        }
        if (data instanceof KpiData kpi) {
            return "KPI value: " + (kpi.value() != null ? kpi.value() : "null");
        }
        if (data instanceof ChartData chart) {
            List<String> cats = chart.categories() != null ? chart.categories() : List.of();
            List<String> first3 = cats.stream().limit(3).toList();
            return "Chart: " + cats.size() + " categories " + first3;
        }
        if (data instanceof TableData table) {
            if (table.page() != null) {
                return "Table: " + table.page().totalElements() + " total rows";
            }
            int rowCount = table.rows() != null ? table.rows().size() : 0;
            return "Table: " + rowCount + " rows";
        }
        if (data instanceof PivotTableData pivot) {
            if (pivot.page() != null) {
                return "Pivot table: " + pivot.page().totalElements() + " total rows";
            }
            int rowCount = pivot.rows() != null ? pivot.rows().size() : 0;
            return "Pivot table: " + rowCount + " rows";
        }
        return "Preview executed successfully";
    }
}
