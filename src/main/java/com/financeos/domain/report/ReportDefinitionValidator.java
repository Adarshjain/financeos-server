package com.financeos.domain.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.financeos.core.exception.ValidationException;
import com.financeos.domain.account.cycle.CycleOperators;
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
import com.financeos.domain.report.definition.TableDefinition;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
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
            "previous_year", "today", "yesterday", "current_fy", "prev_fy", "all_time",
            CycleOperators.THIS_CYCLE, CycleOperators.PREVIOUS_CYCLE);
    private static final Set<String> PARAM_DATE_OPS = Set.of("last_x_days", "last_x_months", "last_x_years", "next_x_days");

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
        validateBillingCycleScope(datasource, definition);
    }

    // ------------------------------------------------------------------ Billing cycle

    /**
     * Billing cycles differ per account, so a report that filters by a billing-cycle operator or
     * groups by a billing-cycle field must be limited to exactly one account (an "is" filter, or
     * "in" with a single value, on the datasource's billing-cycle account field).
     */
    private void validateBillingCycleScope(ReportDatasource datasource, ReportDefinition definition) {
        List<FilterClause> filters = filtersOf(definition);
        boolean usesCycle = filters.stream().anyMatch(f -> CycleOperators.isCycle(f.operator()))
                || groupedFields(definition).stream().map(datasource::field)
                        .anyMatch(f -> f != null && f.type() != FieldType.DATE && Boolean.TRUE.equals(f.billingCycle()));
        if (!usesCycle) {
            return;
        }
        String accountField = datasource.billingCycleAccountField();
        if (accountField == null) {
            throw new ValidationException("Billing cycles are not available on this datasource");
        }
        FieldDef account = datasource.field(accountField);
        String label = account != null ? account.label() : accountField;
        long single = filters.stream().filter(f -> accountField.equals(f.field()) && isSingleValue(f)).count();
        if (single != 1 || filters.stream().anyMatch(f -> accountField.equals(f.field()) && !isSingleValue(f))) {
            throw new ValidationException("Billing cycles differ per account: add exactly one '" + label
                    + " is …' filter to use a billing-cycle filter or grouping");
        }
    }

    private static List<FilterClause> filtersOf(ReportDefinition definition) {
        List<FilterClause> filters = switch (definition) {
            case KpiDefinition kpi -> kpi.filters();
            case ChartDefinition chart -> chart.filters();
            case RawTableDefinition raw -> raw.filters();
            case AggregatedTableDefinition aggregated -> aggregated.filters();
            default -> null;
        };
        return filters == null ? List.of() : filters;
    }

    /** One account: "is" (the generic filter check already requires its value) or "in" with one value. */
    private static boolean isSingleValue(FilterClause f) {
        if ("is".equals(f.operator())) {
            return !f.value().isArray();
        }
        return "in".equals(f.operator()) && f.value() != null && f.value().isArray() && f.value().size() == 1;
    }

    /** Every field a definition groups or lists by (dimensions, series, rows, columns). */
    private static List<String> groupedFields(ReportDefinition definition) {
        List<String> out = new ArrayList<>();
        if (definition instanceof ChartDefinition chart) {
            if (chart.dimension() != null) out.add(chart.dimension().field());
            if (chart.series() != null) out.add(chart.series().field());
        } else if (definition instanceof RawTableDefinition raw) {
            if (raw.columns() != null) out.addAll(raw.columns());
        } else if (definition instanceof AggregatedTableDefinition aggregated) {
            if (aggregated.rows() != null) aggregated.rows().forEach(d -> out.add(d.field()));
            if (aggregated.columns() != null) aggregated.columns().forEach(d -> out.add(d.field()));
        }
        return out;
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
        for (String column : table.columns()) {
            FieldDef field = requireField(datasource, column);
            requireAllowedIn(field, ReportType.TABLE, "column");
        }
        validateSortKeys(table.sort(), sortableKeys(table));
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
        if (!isEmpty(table.columns())) {
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
        }
        validateSortKeys(table.sort(), sortableKeys(table));
        validateFilters(datasource, table.filters());
    }

    /**
     * Checks a run-time (header) sort clause against the same rules as a saved sort: a raw
     * table sorts by one of its columns; a pivot by a row dimension, or by a measure key
     * ({@code field_agg}) only when it has no column dimensions. The table itself is assumed valid.
     *
     * @throws ValidationException when the clause's key is not sortable for {@code table}
     */
    public void validateRuntimeSort(TableDefinition table, SortClause clause) {
        validateSortKeys(List.of(clause), sortableKeys(table));
    }

    /** The keys a table may be sorted by (see {@link #validateRuntimeSort}). */
    private static Set<String> sortableKeys(TableDefinition table) {
        Set<String> keys = new HashSet<>();
        switch (table) {
            case RawTableDefinition raw -> keys.addAll(raw.columns());
            case AggregatedTableDefinition aggregated -> {
                aggregated.rows().forEach(dimension -> keys.add(dimension.field()));
                if (isEmpty(aggregated.columns())) {
                    aggregated.measures().forEach(measure -> keys.add(measure.field() + "_" + measure.aggregation().json()));
                }
            }
        }
        return keys;
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
            requireSortKey(clause, validKeys);
        }
    }

    /**
     * Rejects a sort clause whose key is not one of {@code validKeys} (a table's sortable columns),
     * with the message every report table uses.
     *
     * @throws ValidationException when the key is missing or not among {@code validKeys}
     */
    public static void requireSortKey(SortClause clause, Collection<String> validKeys) {
        if (clause == null || clause.key() == null || !validKeys.contains(clause.key())) {
            throw new ValidationException(
                    "Sort key is not an available column: " + (clause == null ? null : clause.key()));
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
        if (!field.canFilter()) {
            throw new ValidationException("'" + field.label() + "' can't be used as a filter; group by it or show it as a column instead");
        }
        boolean cycleOperator = CycleOperators.PUBLIC.contains(operator);
        if (cycleOperator && !Boolean.TRUE.equals(field.billingCycle())) {
            throw new ValidationException("Operator '" + operator + "' is only available on billing-cycle date fields; '"
                    + filter.field() + "' is not one");
        }
        if (!cycleOperator && !registry.operatorsFor(field.type()).contains(operator)) {
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
