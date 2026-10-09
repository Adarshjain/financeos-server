package com.financeos.domain.report.underlying;

import com.fasterxml.jackson.databind.JsonNode;
import com.financeos.api.report.dto.ReportFieldValuesResponse;
import com.financeos.domain.account.cycle.CycleOperators;
import com.financeos.domain.report.ReportFieldValuesService;
import com.financeos.domain.report.datasource.DatasourceCatalog.FieldDef;
import com.financeos.domain.report.datasource.FieldType;
import com.financeos.domain.report.datasource.ReportDatasource;
import com.financeos.domain.report.definition.FilterClause;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

/**
 * Renders a KPI's filter clauses for people, one {@link UnderlyingFilterChip} per clause.
 *
 * <p>The chip text is the clause's right-hand side including the verb ({@code is HDFC Regalia},
 * {@code in Food, Fuel}, {@code is No}); date operators read as words ({@code This month},
 * {@code Last 30 days}, {@code Between 01/10/2026 and 31/10/2026}), dates as dd/mm/yyyy. A
 * computed datasource's dynamic enum filters store ids ({@code idField}); those are shown by the
 * label the field's filter options give them (the id itself when no option matches). Internal
 * clauses ({@link UnderlyingOperators}) are not shown.
 */
@Component
public class UnderlyingFilterChips {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private static final Map<String, String> NAMED_DATE_RANGES = Map.ofEntries(
            Map.entry("today", "Today"),
            Map.entry("yesterday", "Yesterday"),
            Map.entry("this_week", "This week"),
            Map.entry("this_month", "This month"),
            Map.entry("this_year", "This year"),
            Map.entry("previous_week", "Previous week"),
            Map.entry("previous_month", "Previous month"),
            Map.entry("previous_year", "Previous year"),
            Map.entry("current_fy", "This financial year"),
            Map.entry("prev_fy", "Previous financial year"),
            Map.entry("all_time", "All time"));

    private final ReportFieldValuesService fieldValues;

    public UnderlyingFilterChips(ReportFieldValuesService fieldValues) {
        this.fieldValues = fieldValues;
    }

    /** One chip per clause of {@code filters}, in order. */
    public List<UnderlyingFilterChip> describe(ReportDatasource datasource, List<FilterClause> filters) {
        List<FilterClause> shown = filters.stream().filter(f -> !UnderlyingOperators.isInternal(f.operator())).toList();
        Map<String, Map<String, String>> idLabels = idLabels(datasource, shown);
        List<UnderlyingFilterChip> chips = new ArrayList<>();
        for (FilterClause filter : shown) {
            FieldDef field = datasource.field(filter.field());
            String fieldLabel = field != null ? field.label() : filter.field();
            Map<String, String> labels = idLabels.getOrDefault(filter.field(), Map.of());
            chips.add(new UnderlyingFilterChip(filter.field(), fieldLabel, filter.operator(),
                    text(field, filter, labels)));
        }
        return chips;
    }

    /**
     * Option labels by filter value for the id-backed fields among {@code filters}; the options are
     * only loaded when such a filter exists.
     */
    private Map<String, Map<String, String>> idLabels(ReportDatasource datasource, List<FilterClause> filters) {
        boolean needed = filters.stream().map(f -> datasource.field(f.field()))
                .anyMatch(f -> f != null && f.idField() != null);
        if (!needed) {
            return Map.of();
        }
        Map<String, Map<String, String>> out = new HashMap<>();
        fieldValues.values(datasource.name()).options().forEach((field, options) -> out.put(field,
                options.stream().collect(Collectors.toMap(ReportFieldValuesResponse.Option::value,
                        ReportFieldValuesResponse.Option::label, (a, b) -> a))));
        return out;
    }

    private static String text(FieldDef field, FilterClause filter, Map<String, String> labels) {
        FieldType type = field != null ? field.type() : FieldType.STRING;
        JsonNode value = filter.value();
        String op = filter.operator();
        return switch (type) {
            case DATE -> dateText(op, value);
            case BOOLEAN -> "is " + (value != null && value.asBoolean() ? "Yes" : "No");
            case NUMBER -> numberText(op, value);
            case STRING, ENUM -> textText(op, value, labels);
        };
    }

    private static String textText(String op, JsonNode value, Map<String, String> labels) {
        return switch (op) {
            case "is", "exact" -> "is " + label(value, labels);
            case "is_not" -> "is not " + label(value, labels);
            case "in" -> "in " + labelList(value, labels);
            case "not_in" -> "not in " + labelList(value, labels);
            case "starts_with" -> "starts with " + value.asText();
            case "ends_with" -> "ends with " + value.asText();
            case "contains" -> "contains " + value.asText();
            default -> humanize(op) + " " + value.asText();
        };
    }

    private static String numberText(String op, JsonNode value) {
        return switch (op) {
            case "equals" -> "equals " + number(value);
            case "greater_than" -> "greater than " + number(value);
            case "less_than" -> "less than " + number(value);
            case "between" -> "between " + number(value.get("from")) + " and " + number(value.get("to"));
            default -> humanize(op) + " " + number(value);
        };
    }

    private static String dateText(String op, JsonNode value) {
        String named = NAMED_DATE_RANGES.get(op);
        if (named != null) {
            return named;
        }
        if (CycleOperators.isCycle(op)) {
            int cyclesAgo = CycleOperators.cyclesAgo(op, value);
            return switch (cyclesAgo) {
                case 0 -> "This billing cycle";
                case 1 -> "Previous billing cycle";
                default -> cyclesAgo + " billing cycles ago";
            };
        }
        return switch (op) {
            case "is" -> "On " + day(value);
            case "after" -> "After " + day(value);
            case "before" -> "Before " + day(value);
            case "between" -> "Between " + day(value.get("from")) + " and " + day(value.get("to"));
            case "last_x_days" -> "Last " + count(value, "day");
            case "last_x_months" -> "Last " + count(value, "month");
            case "last_x_years" -> "Last " + count(value, "year");
            case "next_x_days" -> "Next " + count(value, "day");
            default -> humanize(op);
        };
    }

    private static String label(JsonNode value, Map<String, String> labels) {
        String raw = value.asText();
        return labels.getOrDefault(raw, raw);
    }

    private static String labelList(JsonNode values, Map<String, String> labels) {
        return StreamSupport.stream(values.spliterator(), false)
                .map(v -> label(v, labels))
                .collect(Collectors.joining(", "));
    }

    private static String number(JsonNode value) {
        return value.decimalValue().stripTrailingZeros().toPlainString();
    }

    private static String day(JsonNode value) {
        return LocalDate.parse(value.asText()).format(DAY);
    }

    private static String count(JsonNode value, String unit) {
        int n = value.get("amount").asInt();
        return n + " " + unit + (n == 1 ? "" : "s");
    }

    private static String humanize(String token) {
        String spaced = token.replace('_', ' ');
        return spaced.isEmpty() ? spaced : Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
    }
}
