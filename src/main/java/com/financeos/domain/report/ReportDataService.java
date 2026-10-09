package com.financeos.domain.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.core.exception.ValidationException;
import com.financeos.core.security.UserContext;
import com.financeos.domain.report.datasource.DatasourceRegistry;
import com.financeos.domain.report.datasource.ReportDatasource;
import com.financeos.domain.report.definition.AggregatedTableDefinition;
import com.financeos.domain.report.definition.ChartDefinition;
import com.financeos.domain.report.definition.KpiDefinition;
import com.financeos.domain.report.definition.RawTableDefinition;
import com.financeos.domain.report.definition.ReportDefinition;
import com.financeos.domain.report.definition.ReportDefinitions;
import com.financeos.domain.report.definition.SortClause;
import com.financeos.domain.report.definition.TableDefinition;
import com.financeos.domain.report.engine.ChartReportExecutor;
import com.financeos.domain.report.engine.InMemoryReportExecutor;
import com.financeos.domain.report.engine.KpiReportExecutor;
import com.financeos.domain.report.engine.ReportData;
import com.financeos.domain.report.engine.RuntimeSort;
import com.financeos.domain.report.engine.TableReportExecutor;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
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

    /**
     * Run a saved report the current user owns. {@code sort} is the run-time header sort
     * ({@code <key>,<asc|desc>}, see {@link RuntimeSort}); null or blank keeps the report's own order.
     */
    @Transactional(readOnly = true)
    public ReportData runSaved(UUID id, Integer page, Integer size, String sort) {
        SortClause runtimeSort = RuntimeSort.parse(sort).orElse(null);
        ResolvedDefinition resolved = resolveSaved(id);
        return dispatch(resolved.datasource(), withRuntimeSort(resolved.definition(), runtimeSort),
                UserContext.getCurrentUserId(), page, size);
    }

    @Transactional(readOnly = true)
    public ReportData runAdHoc(ReportType type, String datasourceName, JsonNode definitionNode,
            Integer page, Integer size) {
        return runDefinition(type, datasourceName, definitionNode, page, size);
    }

    /** {@link #runDefinition(ReportType, String, JsonNode, Integer, Integer, String)} in the definition's own order. */
    @Transactional(readOnly = true)
    public ReportData runDefinition(ReportType type, String datasourceName, JsonNode definitionNode,
            Integer page, Integer size) {
        return runDefinition(type, datasourceName, definitionNode, page, size, null);
    }

    /**
     * Validate a definition node for {@code type} against {@code datasourceName} and run it for the
     * current user. Shared by ad-hoc reports and built-in dashboard widgets (whose definitions are
     * server templates); nothing is persisted. {@code sort} is the run-time header sort
     * ({@code <key>,<asc|desc>}, see {@link RuntimeSort}); null or blank keeps the definition's order.
     */
    @Transactional(readOnly = true)
    public ReportData runDefinition(ReportType type, String datasourceName, JsonNode definitionNode,
            Integer page, Integer size, String sort) {
        SortClause runtimeSort = RuntimeSort.parse(sort).orElse(null);
        ResolvedDefinition resolved = resolveDefinition(type, datasourceName, definitionNode);
        return dispatch(resolved.datasource(), withRuntimeSort(resolved.definition(), runtimeSort),
                UserContext.getCurrentUserId(), page, size);
    }

    /** A validated report definition and the datasource it runs over. */
    public record ResolvedDefinition(ReportDatasource datasource, ReportDefinition definition) {
    }

    /** The validated definition of a saved report the current user owns (404 / 400 otherwise). */
    @Transactional(readOnly = true)
    public ResolvedDefinition resolveSaved(UUID id) {
        Report report = reportService.get(id); // enforces ownership
        ReportDefinition definition = ReportDefinitions.parse(report.getType(), report.getDefinition(), mapper);
        validator.validate(report.getDatasource(), definition);
        return new ResolvedDefinition(registry.byName(report.getDatasource()), definition);
    }

    /**
     * A definition node parsed as {@code type} and validated against {@code datasourceName};
     * 400 when the type is missing, the node holds another type, or the definition is invalid.
     */
    public ResolvedDefinition resolveDefinition(ReportType type, String datasourceName, JsonNode definitionNode) {
        if (type == null) {
            throw new ValidationException("type is required");
        }
        ReportDefinition definition = ReportDefinitions.parse(type, definitionNode, mapper);
        if (!definition.type().equals(type)) {
            throw new ValidationException(
                    "Report definition type mismatch: expected " + type + " but got " + definition.type());
        }
        validator.validate(datasourceName, definition);
        return new ResolvedDefinition(registry.byName(datasourceName), definition);
    }

    /**
     * A table run with a header sort: the clause (checked like a saved sort) replaces the table's
     * own sort for this run only. KPIs and charts have no rows to order and ignore it.
     */
    private ReportDefinition withRuntimeSort(ReportDefinition definition, @Nullable SortClause runtimeSort) {
        if (runtimeSort == null || !(definition instanceof TableDefinition table)) {
            return definition;
        }
        validator.validateRuntimeSort(table, runtimeSort);
        List<SortClause> sort = List.of(runtimeSort);
        return switch (table) {
            case RawTableDefinition raw -> new RawTableDefinition(raw.mode(), raw.columns(), raw.filters(), sort);
            case AggregatedTableDefinition aggregated -> new AggregatedTableDefinition(aggregated.mode(),
                    aggregated.rows(), aggregated.columns(), aggregated.measures(), aggregated.filters(), sort);
        };
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
