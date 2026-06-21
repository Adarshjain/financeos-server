package com.financeos.domain.report.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.financeos.domain.report.datasource.DatasourceCatalog;
import com.financeos.domain.report.datasource.FieldType;
import com.financeos.domain.report.definition.FilterClause;
import com.financeos.domain.report.definition.Granularity;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
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
            Map.entry("isUnderMonitoring", new Mapping("t.is_under_monitoring", null)));

    private final DatasourceCatalog catalog;
    private final DateRangeResolver dateRangeResolver;

    public TransactionQueryBuilder(DatasourceCatalog catalog, DateRangeResolver dateRangeResolver) {
        this.catalog = catalog;
        this.dateRangeResolver = dateRangeResolver;
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
    public String buildWhere(List<FilterClause> filters, boolean includeExcluded, UUID userId,
            Map<String, Object> params, Set<Join> joins) {
        List<String> predicates = new ArrayList<>();
        predicates.add("t.user_id = :userId");
        params.put("userId", userId.toString());
        if (!includeExcluded) {
            predicates.add("t.is_excluded = 0");
        }
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

    /** Formats a truncated bucket date into its display label for the given granularity. */
    public static String bucketLabel(LocalDate bucket, Granularity granularity) {
        return switch (granularity) {
            case DAY, WEEK -> bucket.toString(); // yyyy-MM-dd (week = its Monday)
            case MONTH -> String.format("%04d-%02d", bucket.getYear(), bucket.getMonthValue());
            case QUARTER -> bucket.getYear() + "-Q" + ((bucket.getMonthValue() - 1) / 3 + 1);
            case YEAR -> String.valueOf(bucket.getYear());
        };
    }

    // ------------------------------------------------------------------ predicates

    private String predicate(FilterClause filter, Map<String, Object> params, Set<Join> joins, int idx) {
        String op = filter.operator();
        JsonNode value = filter.value();
        String p = "f" + idx;
        // Category is many-to-many: filter via EXISTS so the join never fans out an aggregate.
        if ("category".equals(filter.field())) {
            return categoryPredicate(op, value, params, p);
        }
        FieldType type = catalog.field(filter.field()).type();
        String expr = expression(filter.field(), joins);
        return switch (type) {
            case NUMBER -> numberPredicate(expr, op, value, params, p);
            case BOOLEAN -> {
                params.put(p, value.asBoolean() ? 1 : 0);
                yield expr + " = :" + p;
            }
            case STRING -> stringPredicate(expr, op, value, params, p);
            case ENUM -> enumPredicate(expr, op, value, params, p);
            case DATE -> datePredicate(expr, op, value, params, p);
        };
    }

    private String categoryPredicate(String op, JsonNode v, Map<String, Object> params, String p) {
        String inner = "SELECT 1 FROM transaction_categories tcx"
                + " JOIN categories cx ON cx.id = tcx.category_id"
                + " WHERE tcx.transaction_id = t.id AND ";
        switch (op) {
            case "is" -> {
                params.put(p, v.asText());
                return "EXISTS (" + inner + "cx.name = :" + p + ")";
            }
            case "is_not" -> {
                params.put(p, v.asText());
                return "NOT EXISTS (" + inner + "cx.name = :" + p + ")";
            }
            case "in" -> {
                params.put(p, textList(v));
                return "EXISTS (" + inner + "cx.name IN (:" + p + "))";
            }
            case "not_in" -> {
                params.put(p, textList(v));
                return "NOT EXISTS (" + inner + "cx.name IN (:" + p + "))";
            }
            default -> throw new IllegalArgumentException("Unsupported category operator: " + op);
        }
    }

    private String numberPredicate(String expr, String op, JsonNode v, Map<String, Object> params, String p) {
        switch (op) {
            case "equals" -> {
                params.put(p, v.decimalValue());
                return expr + " = :" + p;
            }
            case "greater_than" -> {
                params.put(p, v.decimalValue());
                return expr + " > :" + p;
            }
            case "less_than" -> {
                params.put(p, v.decimalValue());
                return expr + " < :" + p;
            }
            case "between" -> {
                params.put(p + "a", v.get("from").decimalValue());
                params.put(p + "b", v.get("to").decimalValue());
                return expr + " BETWEEN :" + p + "a AND :" + p + "b";
            }
            default -> throw new IllegalArgumentException("Unsupported number operator: " + op);
        }
    }

    private String stringPredicate(String expr, String op, JsonNode v, Map<String, Object> params, String p) {
        switch (op) {
            case "exact" -> {
                params.put(p, v.asText());
                return expr + " = :" + p;
            }
            case "contains" -> {
                params.put(p, "%" + escapeLike(v.asText().toLowerCase()) + "%");
                return likeClause(expr, p);
            }
            case "starts_with" -> {
                params.put(p, escapeLike(v.asText().toLowerCase()) + "%");
                return likeClause(expr, p);
            }
            case "ends_with" -> {
                params.put(p, "%" + escapeLike(v.asText().toLowerCase()));
                return likeClause(expr, p);
            }
            case "in" -> {
                params.put(p, textList(v));
                return expr + " IN (:" + p + ")";
            }
            default -> throw new IllegalArgumentException("Unsupported string operator: " + op);
        }
    }

    private String enumPredicate(String expr, String op, JsonNode v, Map<String, Object> params, String p) {
        switch (op) {
            case "is" -> {
                params.put(p, v.asText());
                return expr + " = :" + p;
            }
            case "is_not" -> {
                params.put(p, v.asText());
                return expr + " <> :" + p;
            }
            case "in" -> {
                params.put(p, textList(v));
                return expr + " IN (:" + p + ")";
            }
            case "not_in" -> {
                params.put(p, textList(v));
                return expr + " NOT IN (:" + p + ")";
            }
            default -> throw new IllegalArgumentException("Unsupported enum operator: " + op);
        }
    }

    private String datePredicate(String expr, String op, JsonNode v, Map<String, Object> params, String p) {
        switch (op) {
            case "is" -> {
                params.put(p, LocalDate.parse(v.asText()));
                return expr + " = :" + p;
            }
            case "after" -> {
                params.put(p, LocalDate.parse(v.asText()));
                return expr + " > :" + p;
            }
            case "before" -> {
                params.put(p, LocalDate.parse(v.asText()));
                return expr + " < :" + p;
            }
            case "between" -> {
                params.put(p + "a", LocalDate.parse(v.get("from").asText()));
                params.put(p + "b", LocalDate.parse(v.get("to").asText()));
                return expr + " BETWEEN :" + p + "a AND :" + p + "b";
            }
            default -> {
                // Relative operator (this_month, last_x_days, all_time, ...).
                DateRange range = dateRangeResolver.resolveRelative(op, v);
                if (!range.bounded()) {
                    return null; // all_time imposes no constraint
                }
                params.put(p + "a", range.from());
                params.put(p + "b", range.to());
                return expr + " BETWEEN :" + p + "a AND :" + p + "b";
            }
        }
    }

    private static String likeClause(String expr, String p) {
        return "LOWER(" + expr + ") LIKE :" + p + " ESCAPE '\\'";
    }

    private static List<String> textList(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(node -> out.add(node.asText()));
        return out;
    }

    private static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
