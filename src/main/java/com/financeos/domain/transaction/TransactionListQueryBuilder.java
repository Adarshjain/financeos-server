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

import java.time.LocalDate;
import java.util.*;

/**
 * Builds dynamic native Oracle SQL for the transaction list query, handling
 * filtering, sorting, tenancy, and running balances safely.
 */
@Component
public class TransactionListQueryBuilder {

    public enum Join {
        ACCOUNTS,
        CARDS
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
            Map.entry("accountType", new FieldMetadata("accountType", FieldType.ENUM, "a.type", Join.ACCOUNTS, Set.of("bank_account", "credit_card", "broker", "generic"))),
            Map.entry("cardId", new FieldMetadata("cardId", FieldType.ENUM, "sub.card_id", null, null)),
            Map.entry("card", new FieldMetadata("card", FieldType.ENUM, "ch.person_name", Join.CARDS, null)),
            // category keys (a key includes its sub-categories) and sub-category keys; "uncategorized" = no category;
            // tag ids; "untagged" = no tag. The old tag-name meaning of "category" moved to "tag".
            Map.entry("category", new FieldMetadata("category", FieldType.ENUM, "sub.category_key", null, null)),
            Map.entry("subcategory", new FieldMetadata("subcategory", FieldType.ENUM, "sub.subcategory_key", null, null)),
            Map.entry("uncategorized", new FieldMetadata("uncategorized", FieldType.BOOLEAN, null, null, null)),
            Map.entry("tag", new FieldMetadata("tag", FieldType.ENUM, null, null, null)),
            Map.entry("untagged", new FieldMetadata("untagged", FieldType.BOOLEAN, null, null, null)),
            Map.entry("reviewType", new FieldMetadata("reviewType", FieldType.ENUM, "sub.review_type", null, Set.of("NEEDS_REVIEW", "AUTO_REVIEWED", "MANUALLY_REVIEWED", "NA"))),
            Map.entry("reviewReason", new FieldMetadata("reviewReason", FieldType.ENUM, null, null, java.util.Arrays.stream(ReviewReason.values()).map(Enum::name).collect(java.util.stream.Collectors.toUnmodifiableSet()))),
            Map.entry("isUnderMonitoring", new FieldMetadata("isUnderMonitoring", FieldType.BOOLEAN, "sub.is_under_monitoring", null, null)),
            Map.entry("isExcluded", new FieldMetadata("isExcluded", FieldType.BOOLEAN, "sub.is_excluded", null, null)),
            Map.entry("coveredByStatement", new FieldMetadata("coveredByStatement", FieldType.BOOLEAN, null, Join.ACCOUNTS, null))
    );

    private static final Set<String> ARRAY_OPS = Set.of("in", "not_in");
    private static final Set<String> VALUELESS_DATE_OPS = Set.of(
            "this_month", "this_week", "this_year", "previous_month", "previous_week",
            "previous_year", "today", "yesterday", "current_fy", "prev_fy", "all_time");
    private static final Set<String> PARAM_DATE_OPS = Set.of("last_x_days", "last_x_months", "last_x_years", "next_x_days");

    private final SqlPredicates sqlPredicates;
    private final DatasourceCatalog catalog;

    public TransactionListQueryBuilder(SqlPredicates sqlPredicates, DatasourceCatalog catalog) {
        this.sqlPredicates = sqlPredicates;
        this.catalog = catalog;
    }

    /** Oracle TO_CHAR format the amount search compares its digits against. */
    public static final String AMOUNT_TEXT_FORMAT = "FM99999999999999990.99999999";

    public record QueryResult(String sql, Map<String, Object> params) {}

    /**
     * The windowed CTE chain (running balance, per-day balance, bank anchors, first-mismatch flag). {@code t} is an
     * optional extra predicate on the transactions (also those the statement lines belong to); every figure is a
     * prefix computation per account, so restricting accounts and dates up to the page's last date leaves the
     * rows inside the restriction unchanged.
     */
    private static String balanceCte(String t) {
        return """
            WITH big AS (
              SELECT t.id, t.account_id, t.card_id, t.transaction_date, t.created_at, t.type, t.source,
                     t.amount, t.description, t.sourced_description, t.category_key, t.subcategory_key,
                     t.is_under_monitoring, t.is_excluded, t.review_type,
                     (CASE WHEN t.type = 'CREDIT' THEN t.amount ELSE -t.amount END) AS signed_amount,
                     (COALESCE(abd.opening_balance, 0) +
                      COALESCE(SUM(CASE WHEN t.type = 'CREDIT' THEN t.amount ELSE -t.amount END)
                          OVER (PARTITION BY t.account_id
                                ORDER BY t.transaction_date ASC, t.created_at ASC, t.id ASC), 0)) AS balance
              FROM transactions t
              LEFT JOIN account_bank_details abd ON t.account_id = abd.account_id
              WHERE t.user_id = :userId@@T@@
            ),
            days AS (
              SELECT d.account_id, d.transaction_date,
                     (COALESCE(abd.opening_balance, 0) +
                      COALESCE(SUM(d.net) OVER (PARTITION BY d.account_id ORDER BY d.transaction_date ASC), 0)) AS day_end
              FROM (
                SELECT t.account_id, t.transaction_date,
                       SUM(CASE WHEN t.type = 'CREDIT' THEN t.amount ELSE -t.amount END) AS net
                FROM transactions t
                WHERE t.user_id = :userId@@T@@
                GROUP BY t.account_id, t.transaction_date
              ) d
              LEFT JOIN account_bank_details abd ON d.account_id = abd.account_id
            ),
            sl AS (
              SELECT x.transaction_id, x.balance_after, x.line_index, x.statement_id, x.period_end,
                     x.account_id, x.transaction_date
              FROM (
                SELECT st.transaction_id, st.balance_after, st.line_index, st.statement_id, s.period_end,
                       t.account_id, t.transaction_date,
                       ROW_NUMBER() OVER (PARTITION BY st.transaction_id
                                          ORDER BY s.period_end DESC NULLS LAST, st.statement_id DESC) AS rn
                FROM statement_transactions st
                JOIN statements s ON s.id = st.statement_id
                JOIN transactions t ON t.id = st.transaction_id
                WHERE s.user_id = :userId AND t.user_id = :userId@@T@@
              ) x WHERE x.rn = 1 AND x.balance_after IS NOT NULL
            ),
            anch AS (
              SELECT sl.transaction_id AS id, sl.account_id, sl.transaction_date,
                     (CASE WHEN acc.type = 'credit_card' THEN -sl.balance_after ELSE sl.balance_after END) AS printed,
                     ROW_NUMBER() OVER (PARTITION BY sl.account_id, sl.transaction_date
                                        ORDER BY sl.period_end DESC NULLS LAST, sl.statement_id DESC,
                                                 sl.line_index DESC NULLS LAST, sl.transaction_id DESC) AS rn
              FROM sl
              JOIN accounts acc ON acc.id = sl.account_id
            ),
            mm AS (
              SELECT a.id, a.account_id, a.transaction_date, a.printed,
                     (CASE WHEN ABS(dy.day_end - a.printed) > 0.005 THEN 1 ELSE 0 END) AS mismatch
              FROM anch a
              JOIN days dy ON dy.account_id = a.account_id AND dy.transaction_date = a.transaction_date
              WHERE a.rn = 1
            ),
            flags AS (
              SELECT m.id,
                     (CASE WHEN m.mismatch = 1 AND (m.prev_mismatch IS NULL OR m.prev_mismatch = 0)
                           THEN m.printed ELSE NULL END) AS bank_balance
              FROM (
                SELECT mm.*,
                       LAG(mm.mismatch) OVER (PARTITION BY mm.account_id ORDER BY mm.transaction_date ASC) AS prev_mismatch
                FROM mm
              ) m
            )
            """.replace("@@T@@", t);
    }

    /** Predicate on {@code t}'s alias for the balance of just these accounts, up to a date. */
    private static final String BALANCE_TXN_RESTRICTION = " AND t.account_id IN (:balAccts) AND t.transaction_date <= :balMax";

    /**
     * The page without any window work: ids and account ids of the matching rows in sort order. The running
     * balances of just these rows come from {@link #buildBalanceQuery}.
     */
    public QueryResult buildPageQuery(UUID userId, TransactionSearchCriteria criteria, Pageable pageable) {
        validate(criteria, pageable);
        Map<String, Object> params = new HashMap<>();
        Set<Join> joins = new HashSet<>();
        String whereClause = buildWhereClause(criteria, params, joins);
        String innerSql = """
            SELECT t.id, t.account_id, t.card_id, t.transaction_date, t.created_at, t.type, t.source,
                   t.amount, t.description, t.sourced_description, t.category_key, t.subcategory_key,
                   t.is_under_monitoring, t.is_excluded, t.review_type,
                   (CASE WHEN t.type = 'CREDIT' THEN t.amount ELSE -t.amount END) AS signed_amount
            FROM transactions t
            WHERE t.user_id = :userId
            """;
        params.put("userId", userId.toString());
        StringBuilder sql = new StringBuilder("SELECT sub.id, sub.account_id, sub.transaction_date FROM ( ").append(innerSql).append(" ) sub");
        appendJoinsWhereAndOrder(sql, joins, whereClause, pageable);
        return new QueryResult(sql.toString(), params);
    }

    /**
     * Running balance and bank-mismatch balance of the given page rows, windowed only over the page's accounts and
     * up to {@code maxDate} (the latest date on the page): the same numbers as the unrestricted chain.
     */
    public QueryResult buildBalanceQuery(UUID userId, Collection<String> accountIds, LocalDate maxDate, Collection<String> ids) {
        Map<String, Object> params = new HashMap<>();
        params.put("userId", userId.toString());
        params.put("balAccts", accountIds);
        params.put("balMax", maxDate);
        params.put("balIds", ids);
        // wrapped like the full list query: a top-level WITH over these window CTEs misreads the anchors on H2
        String sql = "SELECT x.id, x.balance, x.bank_balance FROM ( "
                + balanceCte(BALANCE_TXN_RESTRICTION)
                + "SELECT big.id, big.balance, flags.bank_balance FROM big LEFT JOIN flags ON flags.id = big.id"
                + " ) x WHERE x.id IN (:balIds)";
        return new QueryResult(sql, params);
    }

    /**
     * Builds the paginated native SQL query to fetch transaction IDs and running balances.
     */
    public QueryResult buildDataQuery(UUID userId, TransactionSearchCriteria criteria, Pageable pageable) {
        validate(criteria, pageable);

        Map<String, Object> params = new HashMap<>();
        Set<Join> joins = new HashSet<>();

        String whereClause = buildWhereClause(criteria, params, joins);

        // big = every transaction with our running balance (one window pass, as before the bank-balance flag).
        // days = our end-of-day balance per account/day, from a per-day aggregate (tiny, so no second sort of the ledger).
        // sl = each transaction's printed balance from its latest statement; anch = the day's anchor line (bank's last
        // line of the day); mm = anchor vs our day end; flags = the first mismatching day of each run (previous compared
        // day matched, or none) with the printed balance. Only anchor rows are windowed, one per account/day.
        // Printed balances of credit cards state the amount owed, our balance is negative then, so they are negated.
        String innerSql = balanceCte("")
                + "SELECT big.*, flags.bank_balance FROM big LEFT JOIN flags ON flags.id = big.id";
        params.put("userId", userId.toString());

        StringBuilder sql = new StringBuilder();
        sql.append("SELECT sub.id, sub.balance, sub.bank_balance FROM ( ").append(innerSql).append(" ) sub");

        appendJoinsWhereAndOrder(sql, joins, whereClause, pageable);

        return new QueryResult(sql.toString(), params);
    }

    private void appendJoinsWhereAndOrder(StringBuilder sql, Set<Join> joins, String whereClause, Pageable pageable) {
        if (joins.contains(Join.ACCOUNTS)) {
            sql.append(" LEFT JOIN accounts a ON a.id = sub.account_id");
        }
        if (joins.contains(Join.CARDS)) {
            sql.append(" LEFT JOIN cards c ON c.id = sub.card_id LEFT JOIN cardholders ch ON ch.id = c.cardholder_id");
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
                // Amount sorts put foreign charges with no rupee amount yet last, either direction.
                sortClauses.add(expr + " " + dir + (prop.equalsIgnoreCase("amount") ? " NULLS LAST" : ""));

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
    }

    /**
     * Builds the native SQL query to count total matching transactions (efficiently without window functions).
     */
    public QueryResult buildCountQuery(UUID userId, TransactionSearchCriteria criteria) {
        Map<String, Object> params = new HashMap<>();
        Set<Join> joins = new HashSet<>();

        String whereClause = buildWhereClause(criteria, params, joins);

        String innerSql = """
            SELECT t.id, t.account_id, t.card_id, t.transaction_date, t.type, t.source,
                   t.description, t.sourced_description, t.category_key, t.subcategory_key,
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
        if (joins.contains(Join.CARDS)) {
            sql.append(" LEFT JOIN cards c ON c.id = sub.card_id LEFT JOIN cardholders ch ON ch.id = c.cardholder_id");
        }

        sql.append(whereClause);

        return new QueryResult(sql.toString(), params);
    }

    private String buildWhereClause(TransactionSearchCriteria criteria, Map<String, Object> params, Set<Join> joins) {
        List<String> predicates = new ArrayList<>();

        if (criteria.filters() != null) {
            int idx = 0;
            Set<FilterClause> grouped = Collections.newSetFromMap(new IdentityHashMap<>());
            String categoryOr = categoryOrPredicate(criteria.filters(), params, grouped);
            if (categoryOr != null) {
                predicates.add(categoryOr);
            }
            String tagOr = tagOrPredicate(criteria.filters(), params, grouped);
            if (tagOr != null) {
                predicates.add(tagOr);
            }
            for (FilterClause filter : criteria.filters()) {
                if (grouped.contains(filter)) {
                    continue;
                }
                String fieldName = filter.field();
                FieldMetadata meta = FIELDS.get(fieldName);
                String op = filter.operator();
                JsonNode value = filter.value();
                String p = "f" + idx++;

                if (meta.join() != null) {
                    joins.add(meta.join());
                }

                if ("tag".equals(fieldName)) {
                    predicates.add(sqlPredicates.tag(op, value, params, p, "sub.id"));
                } else if ("uncategorized".equals(fieldName) || "untagged".equals(fieldName)) {
                    requireBooleanIs(fieldName, op, value);
                    boolean on = value.asBoolean();
                    if ("uncategorized".equals(fieldName)) {
                        predicates.add(on ? "sub.category_key IS NULL" : "sub.category_key IS NOT NULL");
                    } else {
                        predicates.add((on ? "NOT EXISTS" : "EXISTS")
                                + " (SELECT 1 FROM transaction_tags tgx WHERE tgx.transaction_id = sub.id)");
                    }
                } else if ("category".equals(fieldName) && "is_empty".equals(op)) {
                    predicates.add("sub.category_key IS NULL");
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
            String amountDigits = numericSearchText(criteria.search());
            String amountClause = "";
            if (amountDigits != null) {
                params.put("qAmt", "%" + amountDigits + "%");
                amountClause = "\n                   OR TO_CHAR(ABS(sub.signed_amount), '" + AMOUNT_TEXT_FORMAT + "') LIKE :qAmt";
            }
            predicates.add("""
                (
                      LOWER(sub.description)        LIKE :q ESCAPE '\\'
                   OR LOWER(sub.sourced_description) LIKE :q ESCAPE '\\'
                   OR LOWER(a.name)                 LIKE :q ESCAPE '\\'
                   OR EXISTS (SELECT 1 FROM transaction_tags tcx
                                 JOIN tags cx ON cx.id = tcx.tag_id
                                WHERE tcx.transaction_id = sub.id
                                  AND LOWER(cx.name) LIKE :q ESCAPE '\\')"""
                + amountClause + """

                )
                """);
        }

        if (predicates.isEmpty()) {
            return "";
        }
        return " WHERE " + String.join(" AND ", predicates);
    }

    /**
     * If the search text is a plain number (optional leading rupee sign / minus, comma grouping,
     * one decimal point) returns its bare digits (with the decimal point, trailing decimal zeros dropped), else null.
     */
    public static String numericSearchText(String search) {
        if (search == null) {
            return null;
        }
        String t = search.trim();
        if (t.startsWith("\u20B9")) {
            t = t.substring(1).trim();
        }
        if (t.startsWith("-")) {
            t = t.substring(1).trim();
        }
        t = t.replace(",", "");
        if (!t.matches("\\d+(\\.\\d*)?|\\.\\d+")) {
            return null;
        }
        // Oracle's FM format drops trailing decimal zeros (3600 -> "3600.", 3600.50 -> "3600.5")
        if (t.contains(".")) {
            t = t.replaceAll("0+$", "");
        }
        return t;
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
        if ("uncategorized".equals(meta.name()) || "untagged".equals(meta.name())) {
            requireBooleanIs(meta.name(), operator, filter.value());
            return;
        }
        if ("category".equals(meta.name()) && "is_empty".equals(operator)) {
            // "No category": takes no value.
            if (filter.value() != null && !filter.value().isNull()) {
                throw new ValidationException("Operator 'is_empty' does not take a value");
            }
            return;
        }
        if (!catalog.operatorsFor(meta.type()).contains(operator)) {
            throw new ValidationException("Operator '" + operator + "' is not valid for field '"
                    + filter.field() + "' (" + meta.type().json() + ")");
        }
        validateFilterValue(meta, operator, filter.value());
    }

    private static boolean positive(FilterClause f, String field, String... ops) {
        return field.equals(f.field()) && List.of(ops).contains(f.operator());
    }

    private static List<String> texts(JsonNode value) {
        List<String> out = new ArrayList<>();
        if (value == null || value.isNull()) {
            return out;
        }
        if (value.isArray()) {
            value.forEach(n -> out.add(n.asText()));
        } else {
            out.add(value.asText());
        }
        return out;
    }

    /**
     * The positive category clauses (uncategorized=true, category is_empty/is/in, subcategory is/in) widen each other:
     * (no category) OR (category in keys) OR (subcategory in subs). A sub-category whose category is also chosen
     * narrows that category to the sub instead. Clauses used are added to {@code grouped}; the negated ones stay ANDed.
     */
    private String categoryOrPredicate(List<FilterClause> filters, Map<String, Object> params, Set<FilterClause> grouped) {
        boolean none = false;
        Set<String> cats = new LinkedHashSet<>();
        Set<String> subs = new LinkedHashSet<>();
        for (FilterClause f : filters) {
            if (positive(f, "uncategorized", "is") && f.value() != null && f.value().isBoolean() && f.value().asBoolean()) {
                none = true;
            } else if (positive(f, "category", "is_empty")) {
                none = true;
            } else if (positive(f, "category", "is", "in")) {
                cats.addAll(texts(f.value()));
            } else if (positive(f, "subcategory", "is", "in")) {
                subs.addAll(texts(f.value()));
            } else {
                continue;
            }
            grouped.add(f);
        }
        if (grouped.stream().noneMatch(f -> f.field().equals("uncategorized") || f.field().equals("category")
                || f.field().equals("subcategory"))) {
            return null;
        }
        com.financeos.domain.category.CategoryCatalog cc = com.financeos.domain.category.CategoryCatalog.shared();
        Set<String> narrowed = new HashSet<>();
        for (String sub : subs) {
            String owner = cc.subcategory(sub).map(com.financeos.domain.category.CategoryCatalog.Sub::categoryKey)
                    .orElse(sub.contains(".") ? sub.substring(0, sub.indexOf('.')) : null);
            if (owner != null && cats.contains(owner)) {
                narrowed.add(owner);
            }
        }
        List<String> parts = new ArrayList<>();
        if (none) {
            parts.add("sub.category_key IS NULL");
        }
        List<String> whole = cats.stream().filter(c -> !narrowed.contains(c)).toList();
        if (!whole.isEmpty()) {
            params.put("gc", whole);
            parts.add("sub.category_key IN (:gc)");
        }
        if (!subs.isEmpty()) {
            params.put("gs", new ArrayList<>(subs));
            parts.add("sub.subcategory_key IN (:gs)");
        }
        if (parts.isEmpty()) {
            return null;
        }
        return "(" + String.join(" OR ", parts) + ")";
    }

    /** untagged=true with tag is/in: (no tags) OR (has any of the tags); negated tag clauses stay ANDed. */
    private String tagOrPredicate(List<FilterClause> filters, Map<String, Object> params, Set<FilterClause> grouped) {
        boolean none = false;
        Set<String> tags = new LinkedHashSet<>();
        List<FilterClause> used = new ArrayList<>();
        for (FilterClause f : filters) {
            if (positive(f, "untagged", "is") && f.value() != null && f.value().isBoolean() && f.value().asBoolean()) {
                none = true;
            } else if (positive(f, "tag", "is", "in")) {
                tags.addAll(texts(f.value()));
            } else {
                continue;
            }
            used.add(f);
        }
        if (used.size() < 2 || (tags.isEmpty())) {
            return null; // a single positive clause keeps its own predicate
        }
        grouped.addAll(used);
        params.put("gt", new ArrayList<>(tags));
        String has = "EXISTS (SELECT 1 FROM transaction_tags tgy WHERE tgy.transaction_id = sub.id AND tgy.tag_id IN (:gt))";
        return none
                ? "(NOT EXISTS (SELECT 1 FROM transaction_tags tgn WHERE tgn.transaction_id = sub.id) OR " + has + ")"
                : has;
    }

    /** The flag fields take {@code is} and a boolean. */
    private static void requireBooleanIs(String field, String operator, JsonNode value) {
        if (!"is".equals(operator)) {
            throw new ValidationException("Operator '" + operator + "' is not valid for field '" + field + "'");
        }
        if (value == null || !value.isBoolean()) {
            throw new ValidationException("'" + field + "' requires a boolean value");
        }
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
