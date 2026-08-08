package com.financeos.domain.report.engine;

import com.financeos.domain.report.datasource.DatasourceCatalog.FieldDef;
import com.financeos.domain.report.datasource.FieldType;
import com.financeos.domain.report.definition.FilterClause;
import com.financeos.domain.report.definition.Granularity;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.WeekFields;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public abstract class AbstractReportQueryBuilder implements ReportQueryBuilder {

    public record Mapping(String expression, String joinKey) {
        public Mapping(String expression) {
            this(expression, null);
        }
    }

    protected final Map<String, Mapping> fields;
    protected final Map<String, FieldDef> catalogFields;
    protected final SqlPredicates sqlPredicates;
    protected final DateRangeResolver dateRangeResolver;

    protected AbstractReportQueryBuilder(
            Map<String, Mapping> fields,
            Map<String, FieldDef> catalogFields,
            SqlPredicates sqlPredicates,
            DateRangeResolver dateRangeResolver) {
        this.fields = fields;
        this.catalogFields = catalogFields;
        this.sqlPredicates = sqlPredicates;
        this.dateRangeResolver = dateRangeResolver;
    }

    @Override
    public String expression(String field, Set<String> joins) {
        Mapping mapping = fields.get(field);
        if (mapping == null) {
            throw new IllegalArgumentException("Unmapped report field: " + field);
        }
        if (mapping.joinKey() != null) {
            recordJoin(mapping.joinKey(), joins);
        }
        return mapping.expression();
    }

    protected void recordJoin(String joinKey, Set<String> joins) {
        joins.add(joinKey);
    }

    @Override
    public abstract String fromClause(Set<String> joins);

    protected abstract String userScopePredicate(Map<String, Object> params, UUID userId);

    @Override
    public String buildWhere(List<FilterClause> filters, UUID userId, Map<String, Object> params, Set<String> joins) {
        List<String> predicates = new ArrayList<>();
        predicates.add(userScopePredicate(params, userId));
        if (filters != null) {
            int idx = 0;
            for (FilterClause filter : filters) {
                String predicate = predicate(filter, params, joins, idx++);
                if (predicate != null) {
                    predicates.add(predicate);
                }
            }
        }
        return " WHERE " + String.join(" AND ", predicates);
    }

    protected String predicate(FilterClause filter, Map<String, Object> params, Set<String> joins, int idx) {
        String special = specialPredicate(filter, params, joins, idx);
        if (special != null) {
            return special;
        }
        FieldDef fieldDef = catalogFields.get(filter.field());
        if (fieldDef == null) {
            throw new IllegalArgumentException("Unknown field: " + filter.field());
        }
        FieldType type = fieldDef.type();
        String expr = expression(filter.field(), joins);
        return sqlPredicates.build(type, expr, filter.operator(), filter.value(), params, "f" + idx);
    }

    protected String specialPredicate(FilterClause filter, Map<String, Object> params, Set<String> joins, int idx) {
        return null;
    }

    @Override
    public String bucketExpression(String dateExpression, Granularity granularity) {
        int m = dateRangeResolver.getFiscalYearStartMonth();
        return switch (granularity) {
            case DAY -> "TRUNC(" + dateExpression + ")";
            case WEEK -> "TRUNC(" + dateExpression + ", 'IW')";
            case MONTH -> "TRUNC(" + dateExpression + ", 'MM')";
            case QUARTER -> "TRUNC(" + dateExpression + ", 'Q')";
            case YEAR -> "TRUNC(" + dateExpression + ", 'YYYY')";
            case FY -> m == 1
                    ? "TRUNC(" + dateExpression + ", 'YYYY')"
                    : "ADD_MONTHS(TRUNC(ADD_MONTHS(" + dateExpression + ", -" + (m - 1) + "), 'YYYY'), " + (m - 1) + ")";
        };
    }

    private static final DateTimeFormatter DAY_FORMAT = DateTimeFormatter.ofPattern("dd MMM yy", Locale.ENGLISH);
    private static final DateTimeFormatter MONTH_FORMAT = DateTimeFormatter.ofPattern("MMM yy", Locale.ENGLISH);

    public static String bucketLabel(LocalDate bucket, Granularity granularity) {
        return BucketLabels.bucketLabel(bucket, granularity, 4);
    }

    public static String bucketLabel(LocalDate bucket, Granularity granularity, int fiscalStartMonth) {
        return BucketLabels.bucketLabel(bucket, granularity, fiscalStartMonth);
    }
}
