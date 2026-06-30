package com.financeos.domain.report.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.financeos.domain.report.datasource.FieldType;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Shared native-SQL predicate builder helper.
 * Provides consistent SQL semantics for filters across reports and lists.
 */
@Component
public class SqlPredicates {

    private final DateRangeResolver dateRangeResolver;

    public SqlPredicates(DateRangeResolver dateRangeResolver) {
        this.dateRangeResolver = dateRangeResolver;
    }

    /**
     * Builds a bound predicate string for standard types, mutating the params map.
     * Returns null if no constraint (e.g., all_time relative date).
     */
    public String build(FieldType type, String expr, String op, JsonNode value, Map<String, Object> params, String p) {
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

    /**
     * Category is a many-to-many relationship: filter via EXISTS so the join never fans out an aggregate.
     * Binds category names to parameters.
     */
    public String category(String op, JsonNode v, Map<String, Object> params, String p, String txIdRef) {
        String inner = "SELECT 1 FROM transaction_categories tcx"
                + " JOIN categories cx ON cx.id = tcx.category_id"
                + " WHERE tcx.transaction_id = " + txIdRef + " AND ";
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

    public static String likeClause(String expr, String p) {
        return "LOWER(" + expr + ") LIKE :" + p + " ESCAPE '\\'";
    }

    public static List<String> textList(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(node -> out.add(node.asText()));
        return out;
    }

    public static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
