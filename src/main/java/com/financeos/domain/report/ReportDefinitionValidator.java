package com.financeos.domain.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.financeos.core.exception.ValidationException;
import com.financeos.domain.report.datasource.Aggregation;
import com.financeos.domain.report.datasource.DatasourceCatalog.FieldDef;
import com.financeos.domain.report.datasource.DatasourceRegistry;
import com.financeos.domain.report.datasource.FieldRole;
import com.financeos.domain.report.datasource.FieldType;
import com.financeos.domain.report.datasource.ReportDatasource;
import com.financeos.domain.report.definition.AggregatedTableDefinition;
import com.financeos.domain.report.definition.ChartDefinition;
import com.financeos.domain.report.definition.DimensionRef;
import com.financeos.domain.report.definition.FilterClause;
import com.financeos.domain.report.definition.KpiDefinition;
import com.financeos.domain.report.definition.MeasureRef;
import com.financeos.domain.report.definition.RawTableDefinition;
import com.financeos.domain.report.definition.ReportDefinition;
import com.financeos.domain.report.definition.SortClause;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Validates a {@link ReportDefinition} against a {@link ReportDatasource}: every referenced
 * field must exist and be used in a role/report-type the catalog permits, and every filter
 * operator + value must be legal for its field type. Throws {@link ValidationException} on the
 * first problem found.
 */
@Component
public class ReportDefinitionValidator {

    private static final Set<String> ARRAY_OPS = Set.of("in", "not_in");
    private static final Set<String> VALUELESS_DATE_OPS = Set.of(
            "this_month", "this_week", "this_year", "previous_month", "previous_week",
            "previous_year", "today", "yesterday", "current_fy", "prev_fy", "all_time");
    private static final Set<String> PARAM_DATE_OPS = Set.of("last_x_days", "last_x_months", "last_x_years");

    private final DatasourceRegistry registry;

    public ReportDefinitionValidator(DatasourceRegistry registry) {
        this.registry = registry;
    }

    public void validate(String datasourceName, ReportDefinition definition) {
        if (datasourceName == null || !registry.isKnown(datasourceName)) {
            throw new ValidationException("Unknown report datasource: " + datasourceName);
        }
        if (definition == null) {
            throw new ValidationException("Report definition is required");
        }
        ReportDatasource datasource = registry.byName(datasourceName);
        if (definition instanceof KpiDefinition kpi) {
            validateKpi(datasource, kpi);
        } else if (definition instanceof ChartDefinition chart) {
            validateChart(datasource, chart);
        } else if (definition instanceof RawTableDefinition raw) {
            validateRawTable(datasource, raw);
        } else if (definition instanceof AggregatedTableDefinition aggregated) {
            validateAggregatedTable(datasource, aggregated);
        } else {
            throw new ValidationException("Unsupported report definition type");
        }
    }

    // ------------------------------------------------------------------ KPI

    private void validateKpi(ReportDatasource datasource, KpiDefinition kpi) {
        FieldDef measure = requireMeasure(datasource, kpi.measure(), ReportType.KPI);
        requireAggregation(measure, kpi.aggregation());
        validateFilters(datasource, kpi.filters());
    }

    // ------------------------------------------------------------------ Chart

    private void validateChart(ReportDatasource datasource, ChartDefinition chart) {
        if (chart.chartType() == null) {
            throw new ValidationException("chartType is required for a Chart report");
        }
        if (chart.dimension() == null) {
            throw new ValidationException("dimension is required for a Chart report");
        }
        validateDimension(datasource, chart.dimension(), ReportType.CHART, "dimension");
        if (chart.series() != null) {
            validateDimension(datasource, chart.series(), ReportType.CHART, "series");
            if (chart.series().field().equals(chart.dimension().field())) {
                throw new ValidationException("Chart series must differ from the dimension field");
            }
        }
        if (chart.measure() == null) {
            throw new ValidationException("measure is required for a Chart report");
        }
        validateMeasureRef(datasource, chart.measure(), ReportType.CHART);
        validateFilters(datasource, chart.filters());
    }

    // ------------------------------------------------------------------ Table

    private void validateRawTable(ReportDatasource datasource, RawTableDefinition table) {
        if (isEmpty(table.columns())) {
            throw new ValidationException("A raw table requires at least one column");
        }
        Set<String> sortKeys = new HashSet<>();
        for (String column : table.columns()) {
            FieldDef field = requireField(datasource, column);
            requireAllowedIn(field, ReportType.TABLE, "column");
            sortKeys.add(column);
        }
        validateSortKeys(table.sort(), sortKeys);
        validateFilters(datasource, table.filters());
    }

    private void validateAggregatedTable(ReportDatasource datasource, AggregatedTableDefinition table) {
        if (isEmpty(table.rows())) {
            throw new ValidationException("An aggregated table requires at least one row dimension");
        }
        if (isEmpty(table.measures())) {
            throw new ValidationException("An aggregated table requires at least one measure");
        }
        Set<String> rowFields = new HashSet<>();
        for (DimensionRef dimension : table.rows()) {
            validateDimension(datasource, dimension, ReportType.TABLE, "rows");
            if (!rowFields.add(dimension.field())) {
                throw new ValidationException("Duplicate row dimension: " + dimension.field());
            }
        }
        Set<String> sortKeys = new HashSet<>(rowFields);
        boolean hasColumns = !isEmpty(table.columns());
        if (hasColumns) {
            Set<String> columnFields = new HashSet<>();
            for (DimensionRef dimension : table.columns()) {
                validateDimension(datasource, dimension, ReportType.TABLE, "columns");
                if (!columnFields.add(dimension.field())) {
                    throw new ValidationException("Duplicate column dimension: " + dimension.field());
                }
                if (rowFields.contains(dimension.field())) {
                    throw new ValidationException(
                            "Dimension '" + dimension.field() + "' cannot be both a row and a column");
                }
            }
        }
        for (MeasureRef measure : table.measures()) {
            validateMeasureRef(datasource, measure, ReportType.TABLE);
            if (!hasColumns) {
                sortKeys.add(measure.field() + "_" + measure.aggregation().json());
            }
        }
        validateSortKeys(table.sort(), sortKeys);
        validateFilters(datasource, table.filters());
    }

    // ------------------------------------------------------------------ shared helpers

    private FieldDef requireField(ReportDatasource datasource, String name) {
        if (name == null || name.isBlank()) {
            throw new ValidationException("A field name is required");
        }
        FieldDef field = datasource.field(name);
        if (field == null) {
            throw new ValidationException("Unknown field: " + name);
        }
        return field;
    }

    private void requireAllowedIn(FieldDef field, ReportType type, String usage) {
        if (!field.allowedInReports().contains(type)) {
            throw new ValidationException(
                    "Field '" + field.name() + "' cannot be used as a " + usage + " in a " + type + " report");
        }
    }

    private FieldDef requireMeasure(ReportDatasource datasource, String fieldName, ReportType type) {
        FieldDef field = requireField(datasource, fieldName);
        if (field.role() != FieldRole.MEASURE) {
            throw new ValidationException("Field '" + fieldName + "' is not a measure");
        }
        requireAllowedIn(field, type, "measure");
        return field;
    }

    private void requireAggregation(FieldDef measure, Aggregation aggregation) {
        if (aggregation == null) {
            throw new ValidationException("aggregation is required for measure '" + measure.name() + "'");
        }
        if (measure.aggregations() == null || !measure.aggregations().contains(aggregation)) {
            throw new ValidationException(
                    "Aggregation '" + aggregation.json() + "' is not allowed on '" + measure.name() + "'");
        }
    }

    private void validateMeasureRef(ReportDatasource datasource, MeasureRef measure, ReportType type) {
        if (measure == null || measure.field() == null) {
            throw new ValidationException("measure.field is required");
        }
        FieldDef field = requireMeasure(datasource, measure.field(), type);
        requireAggregation(field, measure.aggregation());
    }

    private void validateDimension(ReportDatasource datasource, DimensionRef dimension, ReportType type, String usage) {
        if (dimension == null || dimension.field() == null) {
            throw new ValidationException(usage + ".field is required");
        }
        FieldDef field = requireField(datasource, dimension.field());
        if (field.role() != FieldRole.DIMENSION) {
            throw new ValidationException("Field '" + dimension.field() + "' is not a dimension");
        }
        requireAllowedIn(field, type, usage);
        if (field.type() == FieldType.DATE) {
            if (dimension.granularity() == null) {
                throw new ValidationException(
                        "granularity is required for the date " + usage + " '" + dimension.field() + "'");
            }
        } else if (dimension.granularity() != null) {
            throw new ValidationException(
                    "granularity is only valid for date fields (" + usage + " '" + dimension.field() + "')");
        }
    }

    private void validateSortKeys(List<SortClause> sort, Set<String> validKeys) {
        if (sort == null) {
            return;
        }
        for (SortClause clause : sort) {
            if (clause == null || clause.key() == null || !validKeys.contains(clause.key())) {
                throw new ValidationException(
                        "Sort key is not an available column: " + (clause == null ? null : clause.key()));
            }
        }
    }

    private static boolean isEmpty(List<?> list) {
        return list == null || list.isEmpty();
    }

    // ------------------------------------------------------------------ filters

    private void validateFilters(ReportDatasource datasource, List<FilterClause> filters) {
        if (filters == null) {
            return;
        }
        for (FilterClause filter : filters) {
            validateFilterClause(datasource, filter);
        }
    }

    private void validateFilterClause(ReportDatasource datasource, FilterClause filter) {
        if (filter == null || filter.field() == null) {
            throw new ValidationException("filter.field is required");
        }
        FieldDef field = requireField(datasource, filter.field());
        String operator = filter.operator();
        if (operator == null) {
            throw new ValidationException("filter.operator is required for '" + filter.field() + "'");
        }
        if (!registry.operatorsFor(field.type()).contains(operator)) {
            throw new ValidationException("Operator '" + operator + "' is not valid for field '"
                    + filter.field() + "' (" + field.type().json() + ")");
        }
        validateFilterValue(field, operator, filter.value());
    }

    private void validateFilterValue(FieldDef field, String operator, JsonNode value) {
        if (field.type() == FieldType.DATE && VALUELESS_DATE_OPS.contains(operator)) {
            if (value != null && !value.isNull()) {
                throw new ValidationException("Operator '" + operator + "' does not take a value");
            }
            return;
        }
        if (field.type() == FieldType.DATE && PARAM_DATE_OPS.contains(operator)) {
            JsonNode amount = value == null ? null : value.get("amount");
            if (amount == null || !amount.isIntegralNumber() || amount.asInt() <= 0) {
                throw new ValidationException("Operator '" + operator + "' requires { amount: positive integer }");
            }
            return;
        }
        if (ARRAY_OPS.contains(operator)) {
            if (value == null || !value.isArray() || value.isEmpty()) {
                throw new ValidationException(
                        "Operator '" + operator + "' on '" + field.name() + "' requires a non-empty array");
            }
            for (JsonNode element : value) {
                validateTextMember(field, element);
            }
            return;
        }
        if ("between".equals(operator)) {
            requireFromTo(field, value);
            return;
        }
        requireScalar(field, operator, value);
    }

    private void requireScalar(FieldDef field, String operator, JsonNode value) {
        if (value == null || value.isNull() || value.isArray() || value.isObject()) {
            throw new ValidationException(
                    "Operator '" + operator + "' on '" + field.name() + "' requires a single value");
        }
        switch (field.type()) {
            case NUMBER -> requireNumber(field, value);
            case BOOLEAN -> {
                if (!value.isBoolean()) {
                    throw new ValidationException("'" + field.name() + "' requires a boolean value");
                }
            }
            case ENUM -> validateTextMember(field, value);
            case STRING, DATE -> {
                if (!value.isTextual()) {
                    throw new ValidationException("'" + field.name() + "' requires a text value");
                }
            }
        }
    }

    private void requireFromTo(FieldDef field, JsonNode value) {
        if (value == null || !value.isObject() || !value.has("from") || !value.has("to")) {
            throw new ValidationException("Operator 'between' on '" + field.name() + "' requires { from, to }");
        }
        JsonNode from = value.get("from");
        JsonNode to = value.get("to");
        if (field.type() == FieldType.NUMBER) {
            requireNumber(field, from);
            requireNumber(field, to);
        } else { // DATE
            if (!from.isTextual() || !to.isTextual()) {
                throw new ValidationException("'between' on '" + field.name() + "' requires text date bounds");
            }
        }
    }

    private void requireNumber(FieldDef field, JsonNode value) {
        if (value == null || !value.isNumber()) {
            throw new ValidationException("'" + field.name() + "' requires a numeric value");
        }
    }

    private void validateTextMember(FieldDef field, JsonNode element) {
        if (element == null || !element.isTextual()) {
            throw new ValidationException("'" + field.name() + "' values must be text");
        }
        if (field.values() != null && !field.values().contains(element.asText())) {
            throw new ValidationException(
                    "'" + element.asText() + "' is not a valid value for '" + field.name() + "'");
        }
    }
}
