package com.financeos.domain.report.datasource;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.financeos.domain.report.ReportType;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The catalog of reportable fields and the operators available per field type for the
 * {@code transactions} data source (v1's only data source). This is the single source of
 * truth served by {@code GET /api/v1/report/datasource} and consumed by report validation.
 *
 * <p>Note: {@code is_excluded} is intentionally NOT a generic field here — it is controlled
 * by the dedicated, required {@code includeExcluded} flag on every report definition.
 */
@Component
public class DatasourceCatalog {

    public static final String TRANSACTIONS = "transactions";

    // ------------------------------------------------------------------ response shapes

    public record FieldDef(
            String name,
            String label,
            FieldType type,
            FieldRole role,
            List<Aggregation> aggregations, // measures only; null otherwise
            List<String> values,            // static enums only; null otherwise
            Boolean dynamic,                // true for user-specific enums; null otherwise
            List<ReportType> allowedInReports) {
    }

    public record DateOperators(List<String> absolute, List<String> relative) {
    }

    public record OperatorCatalog(
            DateOperators date,
            List<String> string,
            List<String> number,
            @JsonProperty("enum") List<String> enumOperators,
            @JsonProperty("boolean") List<String> booleanOperators) {
    }

    public record DatasourceView(List<FieldDef> fields, OperatorCatalog operators) {
    }

    // ------------------------------------------------------------------ operator sets

    private static final DateOperators DATE_OPERATORS = new DateOperators(
            List.of("is", "after", "before", "between"),
            List.of("this_month", "this_week", "this_year", "previous_month",
                    "previous_week", "previous_year", "last_x_days", "last_x_months",
                    "last_x_years", "today", "yesterday", "current_fy", "prev_fy", "all_time"));

    private static final List<String> STRING_OPERATORS =
            List.of("exact", "starts_with", "ends_with", "contains", "in");
    private static final List<String> NUMBER_OPERATORS =
            List.of("equals", "greater_than", "less_than", "between");
    private static final List<String> ENUM_OPERATORS = List.of("is", "is_not", "in", "not_in");
    private static final List<String> BOOLEAN_OPERATORS = List.of("is");

    private static final OperatorCatalog OPERATORS = new OperatorCatalog(
            DATE_OPERATORS, STRING_OPERATORS, NUMBER_OPERATORS, ENUM_OPERATORS, BOOLEAN_OPERATORS);

    // ------------------------------------------------------------------ fields

    private static final List<Aggregation> NUMERIC_AGGS = List.of(
            Aggregation.SUM, Aggregation.AVG, Aggregation.COUNT, Aggregation.MIN, Aggregation.MAX);

    private static final List<ReportType> ALL = List.of(ReportType.KPI, ReportType.CHART, ReportType.TABLE);
    private static final List<ReportType> CHART_TABLE = List.of(ReportType.CHART, ReportType.TABLE);
    private static final List<ReportType> TABLE_ONLY = List.of(ReportType.TABLE);
    private static final List<ReportType> NONE = List.of();

    private static final List<FieldDef> FIELDS = List.of(
            new FieldDef("amount", "Amount", FieldType.NUMBER, FieldRole.MEASURE, NUMERIC_AGGS, null, null, ALL),
            new FieldDef("date", "Date", FieldType.DATE, FieldRole.DIMENSION, null, null, null, CHART_TABLE),
            new FieldDef("type", "Type", FieldType.ENUM, FieldRole.DIMENSION, null,
                    List.of("DEBIT", "CREDIT"), null, CHART_TABLE),
            new FieldDef("category", "Category", FieldType.ENUM, FieldRole.DIMENSION, null, null, true, CHART_TABLE),
            new FieldDef("account", "Account", FieldType.ENUM, FieldRole.DIMENSION, null, null, true, CHART_TABLE),
            new FieldDef("accountType", "Account Type", FieldType.ENUM, FieldRole.DIMENSION, null,
                    List.of("bank_account", "credit_card", "stock", "mutual_fund", "generic"), null, CHART_TABLE),
            new FieldDef("source", "Source", FieldType.ENUM, FieldRole.DIMENSION, null,
                    List.of("gmail", "manual"), null, CHART_TABLE),
            new FieldDef("description", "Description", FieldType.STRING, FieldRole.DIMENSION, null, null, null, TABLE_ONLY),
            new FieldDef("isUnderMonitoring", "Under monitoring", FieldType.BOOLEAN, FieldRole.FILTER, null, null, null, NONE));

    private static final Map<String, FieldDef> BY_NAME = FIELDS.stream()
            .collect(Collectors.toMap(FieldDef::name, f -> f, (a, b) -> a, LinkedHashMap::new));

    // ------------------------------------------------------------------ public API

    /** The full catalog as served by the datasource endpoint. */
    public DatasourceView view() {
        return new DatasourceView(FIELDS, OPERATORS);
    }

    public boolean isKnownDatasource(String datasource) {
        return TRANSACTIONS.equals(datasource);
    }

    /** The field definition for {@code name}, or {@code null} if unknown. */
    public FieldDef field(String name) {
        return BY_NAME.get(name);
    }

    /** All valid operator names for the given field type (date = absolute ∪ relative). */
    public Set<String> operatorsFor(FieldType type) {
        return switch (type) {
            case DATE -> {
                Set<String> s = new HashSet<>(DATE_OPERATORS.absolute());
                s.addAll(DATE_OPERATORS.relative());
                yield s;
            }
            case STRING -> Set.copyOf(STRING_OPERATORS);
            case NUMBER -> Set.copyOf(NUMBER_OPERATORS);
            case ENUM -> Set.copyOf(ENUM_OPERATORS);
            case BOOLEAN -> Set.copyOf(BOOLEAN_OPERATORS);
        };
    }
}
