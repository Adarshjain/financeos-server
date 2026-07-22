package com.financeos.domain.report.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.financeos.domain.report.datasource.DatasourceCatalog;
import com.financeos.domain.report.datasource.FieldType;
import com.financeos.domain.report.definition.FilterClause;
import com.financeos.domain.report.definition.Granularity;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.WeekFields;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Builds native-SQL fragments over the {@code transactions} data source for the report engine.
 *
 * <p>This is the security boundary: every reportable field is mapped here to a fixed SQL
 * expression and the joins it needs, and all filter values are passed as bound parameters —
 * user input is never concatenated into SQL. Because native SQL bypasses Hibernate's
 * {@code userFilter}, {@link #buildWhere} always pins {@code t.user_id}.
 */
@Component
public class TransactionQueryBuilder {

    /** Signed amount, matching the transactions API (CREDIT positive, DEBIT negative). */
    public static final String SIGNED_AMOUNT = "(CASE WHEN t.type = 'CREDIT' THEN t.amount ELSE -t.amount END)";

    /**
     * Categories of a transaction comma-joined into one value (raw-table column), so a
     * transaction stays a single row instead of fanning out across the many-to-many join.
     */
    public static final String CATEGORY_LISTAGG =
            "(SELECT LISTAGG(cx.name, ', ') WITHIN GROUP (ORDER BY cx.name)"
            + " FROM transaction_categories tcx JOIN categories cx ON cx.id = tcx.category_id"
            + " WHERE tcx.transaction_id = t.id)";

    public static final String IS_TRANSFER_LEG =
            "(CASE WHEN EXISTS (SELECT 1 FROM transaction_link_members m JOIN transaction_links l ON l.id = m.link_id WHERE m.transaction_id = t.id AND l.type IN ('TRANSFER','CC_PAYMENT','REVERSAL')) THEN 1 ELSE 0 END)";

    public static final String IS_REFUND_LEG =
            "(CASE WHEN EXISTS (SELECT 1 FROM transaction_link_members m JOIN transaction_links l ON l.id = m.link_id WHERE m.transaction_id = t.id AND l.type = 'REFUND') THEN 1 ELSE 0 END)";

    public static final String LINK_TYPE =
            "(SELECT l.type FROM transaction_link_members m JOIN transaction_links l ON l.id = m.link_id WHERE m.transaction_id = t.id AND ROWNUM = 1)";

    /** Joins a query may need, beyond the base {@code transactions t}. */
    public enum Join {
        ACCOUNTS,
        CATEGORIES
    }

    private record Mapping(String expression, Join join) {
    }

    private static final Map<String, Mapping> FIELDS = Map.ofEntries(
            Map.entry("amount", new Mapping(SIGNED_AMOUNT, null)),
            Map.entry("date", new Mapping("t.transaction_date", null)),
            Map.entry("type", new Mapping("t.type", null)),
            Map.entry("source", new Mapping("t.source", null)),
            Map.entry("description", new Mapping("t.description", null)),
            Map.entry("account", new Mapping("a.name", Join.ACCOUNTS)),
            Map.entry("accountType", new Mapping("a.type", Join.ACCOUNTS)),
            Map.entry("category", new Mapping("c.name", Join.CATEGORIES)),
            Map.entry("isUnderMonitoring", new Mapping("t.is_under_monitoring", null)),
            Map.entry("isExcluded", new Mapping("t.is_excluded", null)),
            Map.entry("isTransferLeg", new Mapping(IS_TRANSFER_LEG, null)),
            Map.entry("isRefundLeg", new Mapping(IS_REFUND_LEG, null)),
            Map.entry("linkType", new Mapping(LINK_TYPE, null)));

    private final DatasourceCatalog catalog;
    private final DateRangeResolver dateRangeResolver;
    private final SqlPredicates sqlPredicates;

    public TransactionQueryBuilder(DatasourceCatalog catalog, DateRangeResolver dateRangeResolver, SqlPredicates sqlPredicates) {
        this.catalog = catalog;
        this.dateRangeResolver = dateRangeResolver;
        this.sqlPredicates = sqlPredicates;
    }

    /** The SQL expression for a catalog field, recording any join it requires into {@code joins}. */
    public String expression(String field, Set<Join> joins) {
        Mapping mapping = FIELDS.get(field);
        if (mapping == null) {
            throw new IllegalArgumentException("Unmapped report field: " + field);
        }
        if (mapping.join() != null) {
            joins.add(mapping.join());
        }
        return mapping.expression();
    }

    /** The {@code FROM transactions t} clause plus whichever joins were collected. */
    public String fromClause(Set<Join> joins) {
        StringBuilder sb = new StringBuilder(" FROM transactions t");
        if (joins.contains(Join.ACCOUNTS)) {
            sb.append(" LEFT JOIN accounts a ON a.id = t.account_id");
        }
        if (joins.contains(Join.CATEGORIES)) {
            sb.append(" LEFT JOIN transaction_categories tc ON tc.transaction_id = t.id")
              .append(" LEFT JOIN categories c ON c.id = tc.category_id");
        }
        return sb.toString();
    }

    /**
     * Builds the {@code WHERE} clause: mandatory user scoping, the include-excluded rule, and
     * each filter as a bound predicate. Populates {@code params} and {@code joins} as a side effect.
     */
    public String buildWhere(List<FilterClause> filters, UUID userId,
            Map<String, Object> params, Set<Join> joins) {
        List<String> predicates = new ArrayList<>();
        predicates.add("t.user_id = :userId");
        params.put("userId", userId.toString());
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

    /** A date-bucket expression for grouping (returns a truncated DATE). */
    public String bucketExpression(String dateExpression, Granularity granularity) {
        return switch (granularity) {
            case DAY -> "TRUNC(" + dateExpression + ")";
            case WEEK -> "TRUNC(" + dateExpression + ", 'IW')";
            case MONTH -> "TRUNC(" + dateExpression + ", 'MM')";
            case QUARTER -> "TRUNC(" + dateExpression + ", 'Q')";
            case YEAR -> "TRUNC(" + dateExpression + ", 'YYYY')";
        };
    }

    private static final DateTimeFormatter DAY_FORMAT = DateTimeFormatter.ofPattern("dd MMM yy", Locale.ENGLISH);
    private static final DateTimeFormatter MONTH_FORMAT = DateTimeFormatter.ofPattern("MMM yy", Locale.ENGLISH);

    /**
     * Formats a truncated bucket date into a granularity-appropriate display label:
     * day "15 Jun 26", week "W12 26" (ISO), month "Jun 26", quarter "Q3 26", year "2026".
     */
    public static String bucketLabel(LocalDate bucket, Granularity granularity) {
        return switch (granularity) {
            case DAY -> bucket.format(DAY_FORMAT);
            case WEEK -> "W" + bucket.get(WeekFields.ISO.weekOfWeekBasedYear())
                    + " " + twoDigitYear(bucket.get(WeekFields.ISO.weekBasedYear()));
            case MONTH -> bucket.format(MONTH_FORMAT);
            case QUARTER -> "Q" + ((bucket.getMonthValue() - 1) / 3 + 1) + " " + twoDigitYear(bucket.getYear());
            case YEAR -> String.valueOf(bucket.getYear());
        };
    }

    private static String twoDigitYear(int year) {
        return String.format("%02d", Math.floorMod(year, 100));
    }

    // ------------------------------------------------------------------ predicates

    private String predicate(FilterClause filter, Map<String, Object> params, Set<Join> joins, int idx) {
        String op = filter.operator();
        JsonNode value = filter.value();
        String p = "f" + idx;
        // Category is many-to-many: filter via EXISTS so the join never fans out an aggregate.
        if ("category".equals(filter.field())) {
            return sqlPredicates.category(op, value, params, p, "t.id");
        }
        FieldType type = catalog.field(filter.field()).type();
        String expr = expression(filter.field(), joins);
        return sqlPredicates.build(type, expr, op, value, params, p);
    }
}
