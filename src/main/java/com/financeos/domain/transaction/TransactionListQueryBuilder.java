package com.financeos.domain.transaction;

import com.fasterxml.jackson.databind.JsonNode;
import com.financeos.core.exception.ValidationException;
import com.financeos.domain.report.datasource.DatasourceCatalog;
import com.financeos.domain.report.datasource.FieldType;
import com.financeos.domain.report.definition.FilterClause;
import com.financeos.domain.report.engine.SqlPredicates;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Builds dynamic native Oracle SQL for the transaction list query, handling
 * filtering, sorting, tenancy, and running balances safely.
 */
@Component
public class TransactionListQueryBuilder {

    public enum Join {
        ACCOUNTS
    }

    private record FieldMetadata(
            String name,
            FieldType type,
            String expression,
            Join join,
            Set<String> validValues
    ) {}

    private static final Map<String, FieldMetadata> FIELDS = Map.ofEntries(
            Map.entry("amount", new FieldMetadata("amount", FieldType.NUMBER, "sub.signed_amount", null, null)),
            Map.entry("date", new FieldMetadata("date", FieldType.DATE, "sub.transaction_date", null, null)),
            Map.entry("type", new FieldMetadata("type", FieldType.ENUM, "sub.type", null, Set.of("DEBIT", "CREDIT"))),
            Map.entry("source", new FieldMetadata("source", FieldType.ENUM, "sub.source", null, Set.of("gmail_transaction_alert", "gmail_statement", "manual", "file_upload"))),
            Map.entry("description", new FieldMetadata("description", FieldType.STRING, "sub.description", null, null)),
            Map.entry("accountId", new FieldMetadata("accountId", FieldType.ENUM, "sub.account_id", null, null)),
            Map.entry("account", new FieldMetadata("account", FieldType.ENUM, "a.name", Join.ACCOUNTS, null)),
            Map.entry("accountType", new FieldMetadata("accountType", FieldType.ENUM, "a.type", Join.ACCOUNTS, Set.of("bank_account", "credit_card", "stock", "mutual_fund", "generic"))),
            Map.entry("category", new FieldMetadata("category", FieldType.ENUM, null, null, null)),
            Map.entry("reviewType", new FieldMetadata("reviewType", FieldType.ENUM, "sub.review_type", null, Set.of("NEEDS_REVIEW", "AUTO_REVIEWED", "MANUALLY_REVIEWED", "NA"))),
            Map.entry("reviewReason", new FieldMetadata("reviewReason", FieldType.ENUM, null, null, Set.of("UNRECONCILED", "CATEGORY_UNVERIFIED", "DUPLICATE_SUSPECT"))),
            Map.entry("isUnderMonitoring", new FieldMetadata("isUnderMonitoring", FieldType.BOOLEAN, "sub.is_under_monitoring", null, null)),
            Map.entry("isExcluded", new FieldMetadata("isExcluded", FieldType.BOOLEAN, "sub.is_excluded", null, null)),
            Map.entry("coveredByStatement", new FieldMetadata("coveredByStatement", FieldType.BOOLEAN, null, Join.ACCOUNTS, null))
    );

    private static final Set<String> ARRAY_OPS = Set.of("in", "not_in");
    private static final Set<String> VALUELESS_DATE_OPS = Set.of(
            "this_month", "this_week", "this_year", "previous_month", "previous_week",
            "previous_year", "today", "yesterday", "current_fy", "prev_fy", "all_time");
    private static final Set<String> PARAM_DATE_OPS = Set.of("last_x_days", "last_x_months", "last_x_years");

    private final SqlPredicates sqlPredicates;
    private final DatasourceCatalog catalog;

    public TransactionListQueryBuilder(SqlPredicates sqlPredicates, DatasourceCatalog catalog) {
        this.sqlPredicates = sqlPredicates;
        this.catalog = catalog;
    }

    public record QueryResult(String sql, Map<String, Object> params) {}

    /**
     * Builds the paginated native SQL query to fetch transaction IDs and running balances.
     */
    public QueryResult buildDataQuery(UUID userId, TransactionSearchCriteria criteria, Pageable pageable) {
        validate(criteria, pageable);

        Map<String, Object> params = new HashMap<>();
        Set<Join> joins = new HashSet<>();

        String whereClause = buildWhereClause(criteria, params, joins);

        String innerSql = """
            SELECT t.id, t.account_id, t.transaction_date, t.created_at, t.type, t.source,
                   t.amount, t.description, t.sourced_description,
                   t.is_under_monitoring, t.is_excluded, t.review_type,
                   (CASE WHEN t.type = 'CREDIT' THEN t.amount ELSE -t.amount END) AS signed_amount,
                   (COALESCE(abd.opening_balance, 0) +
                    SUM(CASE WHEN t.type = 'CREDIT' THEN t.amount ELSE -t.amount END)
                        OVER (PARTITION BY t.account_id
                              ORDER BY t.transaction_date ASC, t.created_at ASC, t.id ASC)) AS balance
            FROM transactions t
            LEFT JOIN account_bank_details abd ON t.account_id = abd.account_id
            WHERE t.user_id = :userId
            """;
        params.put("userId", userId.toString());

        StringBuilder sql = new StringBuilder();
        sql.append("SELECT sub.id, sub.balance FROM ( ").append(innerSql).append(" ) sub");

        if (joins.contains(Join.ACCOUNTS)) {
            sql.append(" LEFT JOIN accounts a ON a.id = sub.account_id");
        }

        sql.append(whereClause);

        // Sort compilation with tie-breakers
        List<String> sortClauses = new ArrayList<>();
        boolean hasDate = false;
        boolean hasCreatedAt = false;
        boolean hasId = false;

        if (pageable.getSort() != null && pageable.getSort().isSorted()) {
            for (Sort.Order order : pageable.getSort()) {
                String prop = order.getProperty();
                String dir = order.getDirection().name();
                String expr = getWhitelistedSortExpression(prop);
                sortClauses.add(expr + " " + dir);

                if (prop.equalsIgnoreCase("date")) hasDate = true;
                if (prop.equalsIgnoreCase("createdAt")) hasCreatedAt = true;
                if (prop.equalsIgnoreCase("id")) hasId = true;
            }
        }

        if (!hasDate) {
            sortClauses.add("sub.transaction_date DESC");
        }
        if (!hasCreatedAt) {
            sortClauses.add("sub.created_at DESC");
        }
        if (!hasId) {
            sortClauses.add("sub.id DESC");
        }

        sql.append(" ORDER BY ").append(String.join(", ", sortClauses));

        return new QueryResult(sql.toString(), params);
    }

    /**
     * Builds the native SQL query to count total matching transactions (efficiently without window functions).
     */
    public QueryResult buildCountQuery(UUID userId, TransactionSearchCriteria criteria) {
        Map<String, Object> params = new HashMap<>();
        Set<Join> joins = new HashSet<>();

        String whereClause = buildWhereClause(criteria, params, joins);

        String innerSql = """
            SELECT t.id, t.account_id, t.transaction_date, t.type, t.source,
                   t.description, t.sourced_description,
                   t.is_under_monitoring, t.is_excluded, t.review_type,
                   (CASE WHEN t.type = 'CREDIT' THEN t.amount ELSE -t.amount END) AS signed_amount
            FROM transactions t
            WHERE t.user_id = :userId
            """;
        params.put("userId", userId.toString());

        StringBuilder sql = new StringBuilder();
        sql.append("SELECT count(*) FROM ( ").append(innerSql).append(" ) sub");

        if (joins.contains(Join.ACCOUNTS)) {
            sql.append(" LEFT JOIN accounts a ON a.id = sub.account_id");
        }

        sql.append(whereClause);

        return new QueryResult(sql.toString(), params);
    }

    private String buildWhereClause(TransactionSearchCriteria criteria, Map<String, Object> params, Set<Join> joins) {
        List<String> predicates = new ArrayList<>();

        if (criteria.filters() != null) {
            int idx = 0;
            for (FilterClause filter : criteria.filters()) {
                String fieldName = filter.field();
                FieldMetadata meta = FIELDS.get(fieldName);
                String op = filter.operator();
                JsonNode value = filter.value();
                String p = "f" + idx++;

                if (meta.join() != null) {
                    joins.add(meta.join());
                }

                if ("category".equals(fieldName)) {
                    predicates.add(sqlPredicates.category(op, value, params, p, "sub.id"));
                } else if ("reviewReason".equals(fieldName)) {
                    String inner = "SELECT 1 FROM transaction_review_reasons r WHERE r.transaction_id = sub.id AND ";
                    switch (op) {
                        case "is" -> {
                            params.put(p, value.asText());
                            predicates.add("EXISTS (" + inner + "r.reason = :" + p + ")");
                        }
                        case "is_not" -> {
                            params.put(p, value.asText());
                            predicates.add("NOT EXISTS (" + inner + "r.reason = :" + p + ")");
                        }
                        case "in" -> {
                            params.put(p, com.financeos.domain.report.engine.SqlPredicates.textList(value));
                            predicates.add("EXISTS (" + inner + "r.reason IN (:" + p + "))");
                        }
                        case "not_in" -> {
                            params.put(p, com.financeos.domain.report.engine.SqlPredicates.textList(value));
                            predicates.add("NOT EXISTS (" + inner + "r.reason IN (:" + p + "))");
                        }
                        default -> throw new ValidationException("Operator '" + op + "' is not valid for field 'reviewReason'");
                    }
                } else if ("coveredByStatement".equals(fieldName)) {
                    if (!"is".equals(op)) {
                        throw new ValidationException("Operator '" + op + "' is not valid for field 'coveredByStatement'");
                    }
                    if (value == null || !value.isBoolean()) {
                        throw new ValidationException("'coveredByStatement' requires a boolean value");
                    }
                    boolean val = value.asBoolean();
                    if (val) {
                        predicates.add("(a.last_statement_date IS NULL OR sub.transaction_date <= a.last_statement_date)");
                    } else {
                        predicates.add("(a.last_statement_date IS NOT NULL AND sub.transaction_date > a.last_statement_date)");
                    }
                } else {
                    String pred = sqlPredicates.build(meta.type(), meta.expression(), op, value, params, p);
                    if (pred != null) {
                        predicates.add(pred);
                    }
                }
            }
        }

        if (criteria.search() != null && !criteria.search().isBlank()) {
            joins.add(Join.ACCOUNTS);
            params.put("q", "%" + SqlPredicates.escapeLike(criteria.search().toLowerCase()) + "%");
            predicates.add("""
                (
                      LOWER(sub.description)        LIKE :q ESCAPE '\\'
                   OR LOWER(sub.sourced_description) LIKE :q ESCAPE '\\'
                   OR LOWER(a.name)                 LIKE :q ESCAPE '\\'
                   OR EXISTS (SELECT 1 FROM transaction_categories tcx
                                 JOIN categories cx ON cx.id = tcx.category_id
                                WHERE tcx.transaction_id = sub.id
                                  AND LOWER(cx.name) LIKE :q ESCAPE '\\')
                )
                """);
        }

        if (predicates.isEmpty()) {
            return "";
        }
        return " WHERE " + String.join(" AND ", predicates);
    }

    private String getWhitelistedSortExpression(String property) {
        if (property.equalsIgnoreCase("date")) {
            return "sub.transaction_date";
        } else if (property.equalsIgnoreCase("amount")) {
            return "sub.signed_amount";
        } else if (property.equalsIgnoreCase("createdAt")) {
            return "sub.created_at";
        } else if (property.equalsIgnoreCase("id")) {
            return "sub.id";
        } else {
            throw new ValidationException("Unsupported sort property: " + property);
        }
    }

    public void validate(TransactionSearchCriteria criteria, Pageable pageable) {
        if (pageable.getSort() != null && pageable.getSort().isSorted()) {
            for (Sort.Order order : pageable.getSort()) {
                String prop = order.getProperty();
                if (!prop.equalsIgnoreCase("date") && !prop.equalsIgnoreCase("amount")
                        && !prop.equalsIgnoreCase("createdAt") && !prop.equalsIgnoreCase("id")) {
                    throw new ValidationException("Unsupported sort property: " + prop);
                }
            }
        }

        if (criteria.filters() != null) {
            for (FilterClause filter : criteria.filters()) {
                validateFilterClause(filter);
            }
        }
    }

    private void validateFilterClause(FilterClause filter) {
        if (filter == null || filter.field() == null) {
            throw new ValidationException("filter.field is required");
        }
        FieldMetadata meta = FIELDS.get(filter.field());
        if (meta == null) {
            throw new ValidationException("Unknown filter field: " + filter.field());
        }
        String operator = filter.operator();
        if (operator == null) {
            throw new ValidationException("filter.operator is required for '" + filter.field() + "'");
        }
        if (!catalog.operatorsFor(meta.type()).contains(operator)) {
            throw new ValidationException("Operator '" + operator + "' is not valid for field '"
                    + filter.field() + "' (" + meta.type().json() + ")");
        }
        validateFilterValue(meta, operator, filter.value());
    }

    private void validateFilterValue(FieldMetadata meta, String operator, JsonNode value) {
        if (meta.type() == FieldType.DATE && VALUELESS_DATE_OPS.contains(operator)) {
            if (value != null && !value.isNull()) {
                throw new ValidationException("Operator '" + operator + "' does not take a value");
            }
            return;
        }
        if (meta.type() == FieldType.DATE && PARAM_DATE_OPS.contains(operator)) {
            JsonNode amount = value == null ? null : value.get("amount");
            if (amount == null || !amount.isIntegralNumber() || amount.asInt() <= 0) {
                throw new ValidationException("Operator '" + operator + "' requires { amount: positive integer }");
            }
            return;
        }
        if (ARRAY_OPS.contains(operator)) {
            if (value == null || !value.isArray() || value.isEmpty()) {
                throw new ValidationException(
                        "Operator '" + operator + "' on '" + meta.name() + "' requires a non-empty array");
            }
            for (JsonNode element : value) {
                validateTextMember(meta, element);
            }
            return;
        }
        if ("between".equals(operator)) {
            requireFromTo(meta, value);
            return;
        }
        requireScalar(meta, operator, value);
    }

    private void requireScalar(FieldMetadata meta, String operator, JsonNode value) {
        if (value == null || value.isNull() || value.isArray() || value.isObject()) {
            throw new ValidationException(
                    "Operator '" + operator + "' on '" + meta.name() + "' requires a single value");
        }
        switch (meta.type()) {
            case NUMBER -> requireNumber(meta, value);
            case BOOLEAN -> {
                if (!value.isBoolean()) {
                    throw new ValidationException("'" + meta.name() + "' requires a boolean value");
                }
            }
            case ENUM -> validateTextMember(meta, value);
            case STRING, DATE -> {
                if (!value.isTextual()) {
                    throw new ValidationException("'" + meta.name() + "' requires a text value");
                }
            }
        }
    }

    private void requireFromTo(FieldMetadata meta, JsonNode value) {
        if (value == null || !value.isObject() || !value.has("from") || !value.has("to")) {
            throw new ValidationException("Operator 'between' on '" + meta.name() + "' requires { from, to }");
        }
        JsonNode from = value.get("from");
        JsonNode to = value.get("to");
        if (meta.type() == FieldType.NUMBER) {
            requireNumber(meta, from);
            requireNumber(meta, to);
        } else {
            if (!from.isTextual() || !to.isTextual()) {
                throw new ValidationException("'between' on '" + meta.name() + "' requires text date bounds");
            }
        }
    }

    private void requireNumber(FieldMetadata meta, JsonNode value) {
        if (value == null || !value.isNumber()) {
            throw new ValidationException("'" + meta.name() + "' requires a numeric value");
        }
    }

    private void validateTextMember(FieldMetadata meta, JsonNode element) {
        if (element == null || !element.isTextual()) {
            throw new ValidationException("'" + meta.name() + "' values must be text");
        }
        if (meta.validValues() != null && !meta.validValues().contains(element.asText())) {
            throw new ValidationException(
                    "'" + element.asText() + "' is not a valid value for '" + meta.name() + "'");
        }
    }
}
