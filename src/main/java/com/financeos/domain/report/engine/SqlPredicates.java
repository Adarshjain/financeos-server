package com.financeos.domain.report.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.financeos.domain.report.datasource.FieldType;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
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
     * Tags by name (the reports "tag" field; they were the per-user categories): a many-to-many
     * relationship, so filter via EXISTS so the join never fans out an aggregate.
     * Binds tag names to parameters.
     */
    public String category(String op, JsonNode v, Map<String, Object> params, String p, String txIdRef) {
        String inner = "SELECT 1 FROM transaction_tags tcx"
                + " JOIN tags cx ON cx.id = tcx.tag_id"
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
            case "is_empty" -> {
                return "NOT EXISTS (SELECT 1 FROM transaction_tags tcx WHERE tcx.transaction_id = " + txIdRef + ")";
            }
            default -> throw new IllegalArgumentException("Unsupported category operator: " + op);
        }
    }

    /** Tags by id, the same shape as {@link #category}: EXISTS so nothing fans out. */
    public String tag(String op, JsonNode v, Map<String, Object> params, String p, String txIdRef) {
        String inner = "SELECT 1 FROM transaction_tags tgx WHERE tgx.transaction_id = " + txIdRef + " AND ";
        switch (op) {
            case "is" -> {
                params.put(p, v.asText());
                return "EXISTS (" + inner + "tgx.tag_id = :" + p + ")";
            }
            case "is_not" -> {
                params.put(p, v.asText());
                return "NOT EXISTS (" + inner + "tgx.tag_id = :" + p + ")";
            }
            case "in" -> {
                params.put(p, textList(v));
                return "EXISTS (" + inner + "tgx.tag_id IN (:" + p + "))";
            }
            case "not_in" -> {
                params.put(p, textList(v));
                return "NOT EXISTS (" + inner + "tgx.tag_id IN (:" + p + "))";
            }
            default -> throw new IllegalArgumentException("Unsupported tag operator: " + op);
        }
    }

    private String numberPredicate(String expr, String op, JsonNode v, Map<String, Object> params, String p) {
        switch (op) {
            case "equals" -> {
                params.put(p, parseNumber(v, "number"));
                return expr + " = :" + p;
            }
            case "greater_than" -> {
                params.put(p, parseNumber(v, "number"));
                return expr + " > :" + p;
            }
            case "less_than" -> {
                params.put(p, parseNumber(v, "number"));
                return expr + " < :" + p;
            }
            case "between" -> {
                params.put(p + "a", parseNumber(v == null ? null : v.get("from"), "from"));
                params.put(p + "b", parseNumber(v == null ? null : v.get("to"), "to"));
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
            // A missing value is never X, so NULL rows pass the negated operators.
            case "is_not" -> {
                params.put(p, v.asText());
                return "(" + expr + " <> :" + p + " OR " + expr + " IS NULL)";
            }
            case "in" -> {
                params.put(p, textList(v));
                return expr + " IN (:" + p + ")";
            }
            case "not_in" -> {
                params.put(p, textList(v));
                return "(" + expr + " NOT IN (:" + p + ") OR " + expr + " IS NULL)";
            }
            default -> throw new IllegalArgumentException("Unsupported enum operator: " + op);
        }
    }

    private String datePredicate(String expr, String op, JsonNode v, Map<String, Object> params, String p) {
        switch (op) {
            case "is" -> {
                params.put(p, parseDate(v, "date"));
                return expr + " = :" + p;
            }
            case "after" -> {
                params.put(p, parseDate(v, "date"));
                return expr + " > :" + p;
            }
            case "before" -> {
                params.put(p, parseDate(v, "date"));
                return expr + " < :" + p;
            }
            case "between" -> {
                params.put(p + "a", parseDate(v == null ? null : v.get("from"), "from"));
                params.put(p + "b", parseDate(v == null ? null : v.get("to"), "to"));
                return expr + " BETWEEN :" + p + "a AND :" + p + "b";
            }
            default -> {
                // Relative operator (this_month, last_x_days, all_time, ...).
                DateRange range;
                try {
                    range = dateRangeResolver.resolveRelative(op, v);
                } catch (DateTimeException e) {
                    throw new IllegalArgumentException("Date filter '" + op + "' is out of range", e);
                }
                if (!range.bounded()) {
                    return null; // all_time imposes no constraint
                }
                params.put(p + "a", range.from());
                params.put(p + "b", range.to());
                return expr + " BETWEEN :" + p + "a AND :" + p + "b";
            }
        }
    }

    /** Parses an ISO yyyy-MM-dd filter value; anything else is a 400 (IllegalArgumentException), never a 500. */
    public static LocalDate parseDate(JsonNode node, String what) {
        if (node == null || node.isNull() || !node.isValueNode()) {
            throw new IllegalArgumentException("Date filter value '" + what + "' is missing or not a date");
        }
        String text = node.asText();
        try {
            return LocalDate.parse(text);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Invalid date '" + text + "' for '" + what + "': expected yyyy-MM-dd", e);
        }
    }

    /** Parses a numeric filter value (a JSON number or numeric text); anything else is a 400, never a silent 0 or a 500. */
    public static BigDecimal parseNumber(JsonNode node, String what) {
        if (node != null && node.isNumber()) {
            return node.decimalValue();
        }
        if (node != null && node.isTextual()) {
            try {
                return new BigDecimal(node.asText().trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Invalid number '" + node.asText() + "' for '" + what + "'", e);
            }
        }
        throw new IllegalArgumentException("Number filter value '" + what + "' is missing or not a number");
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
