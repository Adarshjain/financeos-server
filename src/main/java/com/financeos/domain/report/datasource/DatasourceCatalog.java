package com.financeos.domain.report.datasource;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;
import com.financeos.domain.account.AccountType;
import com.financeos.domain.account.card.CardholderRelationship;
import com.financeos.domain.account.cycle.CycleOperators;
import com.financeos.domain.report.ReportType;
import com.financeos.domain.transaction.ReviewType;
import com.financeos.domain.transaction.TransactionChannel;
import com.financeos.domain.transaction.TransactionSource;
import com.financeos.domain.transaction.link.LinkType;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Catalog shapes, field definitions, and shared operators for report datasources.
 */
@Component
public class DatasourceCatalog {

    public static final String TRANSACTIONS = "transactions";

    // ------------------------------------------------------------------ response shapes

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record FieldDef(
            String name,
            String label,
            FieldType type,
            FieldRole role,
            List<Aggregation> aggregations, // measures only; null otherwise
            List<String> values,            // static enums only; null otherwise
            Boolean dynamic,                // true for user-specific enums; null otherwise
            List<ReportType> allowedInReports,
            String format,                  // "currency" | "number" | "percent"
            /*
             * The stable id of this dynamic enum: on a computed datasource the row key holding it,
             * on a SQL datasource the query-builder mapping that selects it. Filters store that id
             * (the dropdown shows the label), so renames and relabels never break a saved filter;
             * a filter holding the label still matches.
             */
            String idField,
            /*
             * Billing cycles. On a DATE field: the billing-cycle operators apply. On a dimension:
             * the field groups by billing cycle. Either way the report must be limited to one
             * account (see ReportDatasource.billingCycleAccountField).
             */
            Boolean billingCycle,
            /*
             * False when the field cannot be filtered on (it is offered for grouping and columns
             * only: labels, internal ids, counters); null = filterable.
             */
            Boolean filterable,
            /*
             * Static enums only: how each stored value reads for people (e.g. bank_account ->
             * "Bank account"); null when the values read as they are. Display only: filters,
             * sorting and grouping keep the stored values.
             */
            Map<String, String> valueLabels,
            /*
             * Combination guards: which splits/filters this field needs or avoids when it is
             * aggregated (see FieldGuard). Enforced when a report is saved, never when it runs;
             * null when none.
             */
            List<FieldGuard> guards) {

        public FieldDef(String name, String label, FieldType type, FieldRole role,
                        List<Aggregation> aggregations, List<String> values,
                        Boolean dynamic, List<ReportType> allowedInReports, String format, String idField,
                        Boolean billingCycle, Boolean filterable, Map<String, String> valueLabels) {
            this(name, label, type, role, aggregations, values, dynamic, allowedInReports, format, idField,
                    billingCycle, filterable, valueLabels, null);
        }

        public FieldDef(String name, String label, FieldType type, FieldRole role,
                        List<Aggregation> aggregations, List<String> values,
                        Boolean dynamic, List<ReportType> allowedInReports, String format, String idField,
                        Boolean billingCycle, Boolean filterable) {
            this(name, label, type, role, aggregations, values, dynamic, allowedInReports, format, idField,
                    billingCycle, filterable, null, null);
        }

        public FieldDef(String name, String label, FieldType type, FieldRole role,
                        List<Aggregation> aggregations, List<String> values,
                        Boolean dynamic, List<ReportType> allowedInReports, String format, String idField,
                        Boolean billingCycle) {
            this(name, label, type, role, aggregations, values, dynamic, allowedInReports, format, idField,
                    billingCycle, null);
        }

        public FieldDef(String name, String label, FieldType type, FieldRole role,
                        List<Aggregation> aggregations, List<String> values,
                        Boolean dynamic, List<ReportType> allowedInReports, String format, String idField) {
            this(name, label, type, role, aggregations, values, dynamic, allowedInReports, format, idField, null, null);
        }

        /** This field for grouping and columns only: not offered (or accepted) as a filter. */
        public FieldDef notFilterable() {
            return new FieldDef(name, label, type, role, aggregations, values, dynamic, allowedInReports, format,
                    idField, billingCycle, false, valueLabels, guards);
        }

        /** This field with display labels for its stored values, in the given order. */
        public FieldDef withValueLabels(Map<String, String> labels) {
            return new FieldDef(name, label, type, role, aggregations, values, dynamic, allowedInReports, format,
                    idField, billingCycle, filterable, Collections.unmodifiableMap(new LinkedHashMap<>(labels)), guards);
        }

        /** This field with the given combination guards appended. */
        public FieldDef withGuards(FieldGuard... g) {
            List<FieldGuard> all = new ArrayList<>();
            if (guards != null) all.addAll(guards);
            all.addAll(Arrays.asList(g));
            return new FieldDef(name, label, type, role, aggregations, values, dynamic, allowedInReports, format,
                    idField, billingCycle, filterable, valueLabels, List.copyOf(all));
        }

        /** How {@code value} reads for people: its label when it has one, else the value itself. */
        public Object displayValue(Object value) {
            if (valueLabels == null || !(value instanceof String s)) {
                return value;
            }
            return valueLabels.getOrDefault(s, s);
        }

        /** Not a bean getter on purpose: "filterable" must serialize from the component alone. */
        public boolean canFilter() {
            return !Boolean.FALSE.equals(filterable);
        }

        public FieldDef(String name, String label, FieldType type, FieldRole role,
                        List<Aggregation> aggregations, List<String> values,
                        Boolean dynamic, List<ReportType> allowedInReports, String format) {
            this(name, label, type, role, aggregations, values, dynamic, allowedInReports, format, null, null, null);
        }

        /** A DATE dimension that supports the billing-cycle operators. */
        public static FieldDef cycleDate(String name, String label, List<ReportType> allowedInReports) {
            return new FieldDef(name, label, FieldType.DATE, FieldRole.DIMENSION, null, null, null,
                    allowedInReports, null, null, true);
        }

        /**
         * A STRING dimension holding a billing cycle ("start → end"); grouping only — filtering on
         * the label adds nothing over the date field's billing-cycle operators.
         */
        public static FieldDef cycleGrouping(String name, String label, List<ReportType> allowedInReports) {
            return new FieldDef(name, label, FieldType.STRING, FieldRole.DIMENSION, null, null, null,
                    allowedInReports, null, null, true).notFilterable();
        }

        public FieldDef(String name, String label, FieldType type, FieldRole role,
                        List<Aggregation> aggregations, List<String> values,
                        Boolean dynamic, List<ReportType> allowedInReports) {
            this(name, label, type, role, aggregations, values, dynamic, allowedInReports, null, null, null, null);
        }
    }

    public enum GuardKind {
        NO_AGGREGATION("no_aggregation"),
        NEEDS("needs"),
        AVOID("avoid"),
        NO_FILTER("no_filter");

        private final String json;

        GuardKind(String json) {
            this.json = json;
        }

        @JsonValue
        public String json() {
            return json;
        }
    }

    /**
     * A combination guard on a field. {@code no_aggregation}: the field can't be aggregated with
     * {@code aggregations}. {@code needs}: when aggregated, the report must split by or filter
     * to one value of one of {@code fields}. {@code avoid}: the report must not split or filter by
     * any of {@code fields}. {@code no_filter}: the field can't be filtered. {@code aggregations}
     * null = every aggregation.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record FieldGuard(GuardKind kind, List<String> fields, Set<Aggregation> aggregations, String reason) {

        private static final Set<Aggregation> NOT_COUNT = Collections.unmodifiableSet(EnumSet.of(
                Aggregation.SUM, Aggregation.AVG, Aggregation.MIN, Aggregation.MAX));

        public static FieldGuard noAggregation(Set<Aggregation> aggs, String reason) {
            return new FieldGuard(GuardKind.NO_AGGREGATION, null, aggs, reason);
        }

        public static FieldGuard needs(List<String> fields, String reason) {
            return new FieldGuard(GuardKind.NEEDS, fields, NOT_COUNT, reason);
        }

        public static FieldGuard needs(List<String> fields, Set<Aggregation> aggs, String reason) {
            return new FieldGuard(GuardKind.NEEDS, fields, aggs, reason);
        }

        public static FieldGuard avoid(List<String> fields, String reason) {
            return new FieldGuard(GuardKind.AVOID, fields, null, reason);
        }

        public static FieldGuard noFilter(String reason) {
            return new FieldGuard(GuardKind.NO_FILTER, null, null, reason);
        }

        /** True when this guard applies to {@code aggregation} (null aggregations = all). */
        public boolean covers(Aggregation aggregation) {
            return aggregations == null || aggregations.contains(aggregation);
        }
    }

    /** {@code cycle} operators apply only to DATE fields flagged {@code billingCycle}. */
    public record DateOperators(List<String> absolute, List<String> relative, List<String> cycle) {
    }

    public record OperatorCatalog(
            DateOperators date,
            List<String> string,
            List<String> number,
            @JsonProperty("enum") List<String> enumOperators,
            @JsonProperty("boolean") List<String> booleanOperators) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SingleDatasourceView(
            String name,
            String label,
            List<FieldDef> fields,
            /* The account filter field billing-cycle reports require (null = no billing cycles). */
            String billingCycleAccountField) {

        public SingleDatasourceView(String name, String label, List<FieldDef> fields) {
            this(name, label, fields, null);
        }
    }

    public record ReportCatalogView(
            List<SingleDatasourceView> datasources,
            OperatorCatalog operators) {
    }

    public record DatasourceView(List<FieldDef> fields, OperatorCatalog operators) {
    }

    // ------------------------------------------------------------------ operator sets

    private static final DateOperators DATE_OPERATORS = new DateOperators(
            List.of("is", "after", "before", "between"),
            List.of("this_month", "this_week", "this_year", "previous_month",
                    "previous_week", "previous_year", "last_x_days", "last_x_months",
                    "last_x_years", "next_x_days", "today", "yesterday", "current_fy", "prev_fy", "all_time"),
            CycleOperators.PUBLIC);

    private static final List<String> STRING_OPERATORS =
            List.of("exact", "starts_with", "ends_with", "contains", "in");
    private static final List<String> NUMBER_OPERATORS =
            List.of("equals", "greater_than", "less_than", "between");
    private static final List<String> ENUM_OPERATORS = List.of("is", "is_not", "in", "not_in");
    private static final List<String> BOOLEAN_OPERATORS = List.of("is");

    public static final OperatorCatalog OPERATORS = new OperatorCatalog(
            DATE_OPERATORS, STRING_OPERATORS, NUMBER_OPERATORS, ENUM_OPERATORS, BOOLEAN_OPERATORS);

    // ------------------------------------------------------------------ fields

    private static final List<Aggregation> NUMERIC_AGGS = List.of(
            Aggregation.SUM, Aggregation.AVG, Aggregation.COUNT, Aggregation.MIN, Aggregation.MAX);

    private static final List<ReportType> ALL = List.of(ReportType.KPI, ReportType.CHART, ReportType.TABLE);
    private static final List<ReportType> CHART_TABLE = List.of(ReportType.CHART, ReportType.TABLE);
    private static final List<ReportType> TABLE_ONLY = List.of(ReportType.TABLE);
    private static final List<ReportType> NONE = List.of();

    private static final List<FieldDef> FIELDS = List.of(
            new FieldDef("amount", "Amount", FieldType.NUMBER, FieldRole.MEASURE, NUMERIC_AGGS, null, null, ALL, "currency"),
            // Spend view of the same money: debits positive, credits (refunds, income) negative.
            new FieldDef("spend", "Spend", FieldType.NUMBER, FieldRole.MEASURE, NUMERIC_AGGS, null, null, ALL, "currency"),
            FieldDef.cycleDate("date", "Date", CHART_TABLE),
            new FieldDef("type", "Type", FieldType.ENUM, FieldRole.DIMENSION, null,
                    List.of("DEBIT", "CREDIT"), null, CHART_TABLE),
            new FieldDef("category", "Category", FieldType.ENUM, FieldRole.DIMENSION, null, null, true, CHART_TABLE),
            new FieldDef("account", "Account", FieldType.ENUM, FieldRole.DIMENSION, null, null, true, CHART_TABLE,
                    null, "accountId"),
            new FieldDef("accountType", "Account Type", FieldType.ENUM, FieldRole.DIMENSION, null,
                    names(AccountType.values()), null, CHART_TABLE),
            new FieldDef("source", "Source", FieldType.ENUM, FieldRole.DIMENSION, null,
                    names(TransactionSource.values()), null, CHART_TABLE),
            new FieldDef("description", "Description", FieldType.STRING, FieldRole.DIMENSION, null, null, null, TABLE_ONLY),
            new FieldDef("isUnderMonitoring", "Under monitoring", FieldType.BOOLEAN, FieldRole.FILTER, null, null, null, NONE),
            new FieldDef("isExcluded", "Is Excluded", FieldType.BOOLEAN, FieldRole.FILTER, null, null, null, NONE),
            new FieldDef("isTransferLeg", "Is transfer leg", FieldType.BOOLEAN, FieldRole.FILTER, null, null, null, NONE),
            new FieldDef("isRefundLeg", "Is refund leg", FieldType.BOOLEAN, FieldRole.FILTER, null, null, null, NONE),
            new FieldDef("isLendingLeg", "Is lending leg", FieldType.BOOLEAN, FieldRole.FILTER, null, null, null, NONE),
            new FieldDef("isLoanLeg", "Is loan leg", FieldType.BOOLEAN, FieldRole.FILTER, null, null, null, NONE),
            new FieldDef("isDividendLeg", "Is dividend leg", FieldType.BOOLEAN, FieldRole.FILTER, null, null, null, NONE),
            new FieldDef("linkType", "Link type", FieldType.ENUM, FieldRole.DIMENSION, null,
                    names(LinkType.values()), null, CHART_TABLE),
            new FieldDef("settlementDate", "Settlement date", FieldType.DATE, FieldRole.DIMENSION, null, null, null, CHART_TABLE),
            FieldDef.cycleGrouping("billingCycle", "Billing cycle", CHART_TABLE),
            new FieldDef("reviewType", "Review status", FieldType.ENUM, FieldRole.DIMENSION, null,
                    names(ReviewType.values()), null, CHART_TABLE),
            new FieldDef("mcc", "MCC", FieldType.STRING, FieldRole.DIMENSION, null, null, null, CHART_TABLE),
            new FieldDef("channel", "Channel", FieldType.ENUM, FieldRole.DIMENSION, null,
                    names(TransactionChannel.values()), null, CHART_TABLE),
            new FieldDef("isEmi", "Is EMI", FieldType.BOOLEAN, FieldRole.FILTER, null, null, null, NONE),
            new FieldDef("isInternational", "Is international", FieldType.BOOLEAN, FieldRole.FILTER, null, null, null, NONE),
            new FieldDef("instantDiscount", "Instant discount", FieldType.NUMBER, FieldRole.MEASURE, NUMERIC_AGGS, null, null, ALL, "currency"),
            new FieldDef("convenienceFee", "Convenience fee", FieldType.NUMBER, FieldRole.MEASURE, NUMERIC_AGGS, null, null, ALL, "currency"),
            new FieldDef("card", "Card", FieldType.ENUM, FieldRole.DIMENSION, null, null, true, CHART_TABLE),
            new FieldDef("cardholder", "Cardholder", FieldType.ENUM, FieldRole.DIMENSION, null, null, true, CHART_TABLE),
            new FieldDef("cardRelationship", "Card Relationship", FieldType.ENUM, FieldRole.DIMENSION, null,
                    names(CardholderRelationship.values()), null, CHART_TABLE));

    /**
     * Display labels for a static enum's values, in the given order: {@code value1, label1,
     * value2, label2, ...} (see {@link FieldDef#withValueLabels}).
     */
    public static Map<String, String> valueLabels(String... valueLabelPairs) {
        if (valueLabelPairs.length % 2 != 0) {
            throw new IllegalArgumentException("Value labels come in value, label pairs");
        }
        Map<String, String> labels = new LinkedHashMap<>();
        for (int i = 0; i < valueLabelPairs.length; i += 2) {
            labels.put(valueLabelPairs[i], valueLabelPairs[i + 1]);
        }
        return Collections.unmodifiableMap(labels);
    }

    /** A static enum field's values: the enum's names, as stored. */
    private static List<String> names(Enum<?>[] values) {
        return Arrays.stream(values).map(Enum::name).toList();
    }

    private static final Map<String, FieldDef> BY_NAME = FIELDS.stream()
            .collect(Collectors.toMap(FieldDef::name, f -> f, (a, b) -> a, LinkedHashMap::new));

    // ------------------------------------------------------------------ public API

    /**
     * The canonical {@code transactions} field list. {@code TransactionsDatasource} serves this
     * same list through the registry — keep this the single definition.
     */
    public static List<FieldDef> transactionFields() {
        return FIELDS;
    }

    /** The transactions catalog (also used by the transactions list page's filter builder). */
    public DatasourceView view() {
        return new DatasourceView(FIELDS, OPERATORS);
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
                // Billing-cycle operators are per field (billingCycle), validated separately.
                yield s;
            }
            case STRING -> Set.copyOf(STRING_OPERATORS);
            case NUMBER -> Set.copyOf(NUMBER_OPERATORS);
            case ENUM -> Set.copyOf(ENUM_OPERATORS);
            case BOOLEAN -> Set.copyOf(BOOLEAN_OPERATORS);
        };
    }
}
