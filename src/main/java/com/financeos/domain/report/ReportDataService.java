package com.financeos.domain.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.core.exception.ValidationException;
import com.financeos.core.security.UserContext;
import com.financeos.domain.report.definition.ChartDefinition;
import com.financeos.domain.report.definition.KpiDefinition;
import com.financeos.domain.report.definition.ReportDefinition;
import com.financeos.domain.report.definition.ReportDefinitions;
import com.financeos.domain.report.definition.TableDefinition;
import com.financeos.domain.report.engine.ChartReportExecutor;
import com.financeos.domain.report.engine.KpiReportExecutor;
import com.financeos.domain.report.engine.ReportData;
import com.financeos.domain.report.engine.TableReportExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Executes report definitions and returns their computed data. Handles both saved reports
 * (loaded with ownership enforcement) and ad-hoc definitions (validated, not persisted),
 * routing each definition to its type-specific executor.
 */
@Service
public class ReportDataService {

    private final ReportService reportService;
    private final ReportDefinitionValidator validator;
    private final ObjectMapper mapper;
    private final KpiReportExecutor kpiExecutor;
    private final ChartReportExecutor chartExecutor;
    private final TableReportExecutor tableExecutor;

    public ReportDataService(ReportService reportService, ReportDefinitionValidator validator, ObjectMapper mapper,
            KpiReportExecutor kpiExecutor, ChartReportExecutor chartExecutor, TableReportExecutor tableExecutor) {
        this.reportService = reportService;
        this.validator = validator;
        this.mapper = mapper;
        this.kpiExecutor = kpiExecutor;
        this.chartExecutor = chartExecutor;
        this.tableExecutor = tableExecutor;
    }

    @Transactional(readOnly = true)
    public ReportData runSaved(UUID id, Integer page, Integer size) {
        Report report = reportService.get(id); // enforces ownership
        ReportDefinition definition = ReportDefinitions.parse(report.getType(), report.getDefinition(), mapper);
        // Re-validate against the current catalog: a stored definition can become invalid if
        // the datasource catalog changes after the report was saved.
        validator.validate(report.getDatasource(), definition);
        return dispatch(definition, UserContext.getCurrentUserId(), page, size);
    }

    @Transactional(readOnly = true)
    public ReportData runAdHoc(ReportType type, String datasource, JsonNode definitionNode,
            Integer page, Integer size) {
        if (type == null) {
            throw new ValidationException("type is required");
        }
        ReportDefinition definition = ReportDefinitions.parse(type, definitionNode, mapper);
        if (!definition.type().equals(type)) {
            throw new ValidationException(
                    "Report definition type mismatch: expected " + type + " but got " + definition.type());
        }
        validator.validate(datasource, definition);
        return dispatch(definition, UserContext.getCurrentUserId(), page, size);
    }

    private ReportData dispatch(ReportDefinition definition, UUID userId, Integer page, Integer size) {
        if (definition instanceof KpiDefinition kpi) {
            return kpiExecutor.execute(kpi, userId);
        }
        if (definition instanceof ChartDefinition chart) {
            return chartExecutor.execute(chart, userId);
        }
        if (definition instanceof TableDefinition table) {
            return tableExecutor.execute(table, userId, page, size);
        }
        throw new IllegalStateException("Unsupported report definition: " + definition.getClass());
    }
}
