package com.financeos.domain.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.core.exception.ValidationException;
import com.financeos.core.security.UserContext;
import com.financeos.domain.report.datasource.DatasourceRegistry;
import com.financeos.domain.report.datasource.ReportDatasource;
import com.financeos.domain.report.definition.ChartDefinition;
import com.financeos.domain.report.definition.KpiDefinition;
import com.financeos.domain.report.definition.ReportDefinition;
import com.financeos.domain.report.definition.ReportDefinitions;
import com.financeos.domain.report.definition.TableDefinition;
import com.financeos.domain.report.engine.ChartReportExecutor;
import com.financeos.domain.report.engine.InMemoryReportExecutor;
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
    private final DatasourceRegistry registry;
    private final ObjectMapper mapper;
    private final KpiReportExecutor kpiExecutor;
    private final ChartReportExecutor chartExecutor;
    private final TableReportExecutor tableExecutor;
    private final InMemoryReportExecutor inMemoryExecutor;

    public ReportDataService(ReportService reportService, ReportDefinitionValidator validator,
            DatasourceRegistry registry, ObjectMapper mapper,
            KpiReportExecutor kpiExecutor, ChartReportExecutor chartExecutor, TableReportExecutor tableExecutor,
            InMemoryReportExecutor inMemoryExecutor) {
        this.reportService = reportService;
        this.validator = validator;
        this.registry = registry;
        this.mapper = mapper;
        this.kpiExecutor = kpiExecutor;
        this.chartExecutor = chartExecutor;
        this.tableExecutor = tableExecutor;
        this.inMemoryExecutor = inMemoryExecutor;
    }

    @Transactional(readOnly = true)
    public ReportData runSaved(UUID id, Integer page, Integer size) {
        Report report = reportService.get(id); // enforces ownership
        ReportDefinition definition = ReportDefinitions.parse(report.getType(), report.getDefinition(), mapper);
        validator.validate(report.getDatasource(), definition);
        ReportDatasource datasource = registry.byName(report.getDatasource());
        return dispatch(datasource, definition, UserContext.getCurrentUserId(), page, size);
    }

    @Transactional(readOnly = true)
    public ReportData runAdHoc(ReportType type, String datasourceName, JsonNode definitionNode,
            Integer page, Integer size) {
        if (type == null) {
            throw new ValidationException("type is required");
        }
        ReportDefinition definition = ReportDefinitions.parse(type, definitionNode, mapper);
        if (!definition.type().equals(type)) {
            throw new ValidationException(
                    "Report definition type mismatch: expected " + type + " but got " + definition.type());
        }
        validator.validate(datasourceName, definition);
        ReportDatasource datasource = registry.byName(datasourceName);
        return dispatch(datasource, definition, UserContext.getCurrentUserId(), page, size);
    }

    private ReportData dispatch(ReportDatasource datasource, ReportDefinition definition, UUID userId, Integer page, Integer size) {
        if (datasource instanceof com.financeos.domain.report.datasource.ComputedReportDatasource) {
            return switch (definition) {
                case KpiDefinition kpi -> inMemoryExecutor.execute(kpi, datasource, null);
                case ChartDefinition chart -> inMemoryExecutor.execute(chart, datasource, null);
                case TableDefinition table -> inMemoryExecutor.execute(table, datasource, null, page, size);
            };
        }
        return switch (definition) {
            case KpiDefinition kpi -> kpiExecutor.execute(kpi, datasource, userId);
            case ChartDefinition chart -> chartExecutor.execute(chart, datasource, userId);
            case TableDefinition table -> tableExecutor.execute(table, datasource, userId, page, size);
        };
    }
}
