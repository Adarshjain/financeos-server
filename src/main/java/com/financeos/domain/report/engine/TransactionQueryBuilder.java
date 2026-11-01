package com.financeos.domain.report.engine;

import com.financeos.core.time.AppTime;
import com.financeos.domain.account.cycle.BillingCycleService;
import com.financeos.domain.account.cycle.BillingCycles.Cycle;
import com.financeos.domain.account.cycle.CycleOperators;
import com.financeos.domain.account.cycle.CycleWindows;
import com.financeos.domain.report.datasource.DatasourceCatalog.FieldDef;
import com.financeos.domain.report.datasource.FieldType;
import com.financeos.domain.report.definition.FilterClause;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Builds native-SQL fragments over the {@code transactions} data source for the report engine.
 */
public class TransactionQueryBuilder extends AbstractReportQueryBuilder {

    public static final String SIGNED_AMOUNT = "(CASE WHEN t.type = 'CREDIT' THEN t.amount ELSE -t.amount END)";

    /** The spend-positive view of {@link #SIGNED_AMOUNT}: debits positive, credits negative. */
    public static final String SPEND_AMOUNT = "(CASE WHEN t.type = 'DEBIT' THEN t.amount ELSE -t.amount END)";

    /** The transaction's tag names, comma-joined (the raw-table "tag" column). */
    public static final String TAG_LISTAGG =
            "(SELECT LISTAGG(cx.name, ', ') WITHIN GROUP (ORDER BY cx.name)"
            + " FROM transaction_tags tcx JOIN tags cx ON cx.id = tcx.tag_id"
            + " WHERE tcx.transaction_id = t.id)";

    public static final String IS_TRANSFER_LEG =
            "(CASE WHEN EXISTS (SELECT 1 FROM transaction_link_members m JOIN transaction_links l ON l.id = m.link_id WHERE m.transaction_id = t.id AND l.type IN ('TRANSFER','CC_PAYMENT','REVERSAL')) THEN 1 ELSE 0 END)";

    public static final String IS_REFUND_LEG =
            "(CASE WHEN EXISTS (SELECT 1 FROM transaction_link_members m JOIN transaction_links l ON l.id = m.link_id WHERE m.transaction_id = t.id AND l.type = 'REFUND') THEN 1 ELSE 0 END)";

    // A joined column, not a scalar subquery: Oracle rejects subqueries in GROUP BY
    // (ORA-22818), so a chart or pivot grouped by link type needs a plain column.
    public static final String LINK_TYPE = "lk.link_type";

    public static final String IS_LENDING_LEG =
            "(CASE WHEN EXISTS (SELECT 1 FROM lendings x WHERE x.transaction_id = t.id) THEN 1 ELSE 0 END)";

    public static final String IS_LOAN_LEG =
            "(CASE WHEN EXISTS (SELECT 1 FROM loan_payments x WHERE x.transaction_id = t.id) OR EXISTS (SELECT 1 FROM loan_events x WHERE x.transaction_id = t.id) OR EXISTS (SELECT 1 FROM loan_charges x WHERE x.transaction_id = t.id) THEN 1 ELSE 0 END)";

    public static final String IS_DIVIDEND_LEG =
            "(CASE WHEN EXISTS (SELECT 1 FROM dividends x WHERE x.transaction_id = t.id) THEN 1 ELSE 0 END)";

    private static final String TAG = "tag";
    private static final String CATEGORY = "category";
    private static final String SUBCATEGORY = "subcategory";
    private static final String CATEGORY_KEY = "categoryKey";
    private static final String SUBCATEGORY_KEY = "subcategoryKey";
    /** Internal: the plain labels shown in a raw table's category / sub-category column (empty when none). */
    public static final String CATEGORY_LABEL = "categoryLabel";
    public static final String SUBCATEGORY_LABEL = "subcategoryLabel";
    private static final String ACCOUNT = "account";

    public static final String JOIN_ACCOUNTS = "ACCOUNTS";
    public static final String JOIN_TAGS = "TAGS";
    public static final String JOIN_CATEGORY = "CATEGORY";
    public static final String JOIN_SUBCATEGORY = "SUBCATEGORY";
    public static final String JOIN_CARDS = "CARDS";
    public static final String JOIN_LINKS = "LINKS";
    public static final String JOIN_BILLING_CYCLES = "BILLING_CYCLES";

    /**
     * The date a transaction counts on for billing cycles: the settlement (posting) date when
     * present, else the transaction date — banks bill by posting date, and the rewards engine
     * uses the same rule.
     */
    public static final String EFFECTIVE_DATE = "COALESCE(t.settlement_date, t.transaction_date)";

    /** "start → end" of the account's cycle holding the transaction's effective date. */
    public static final String BILLING_CYCLE_DIM =
            "CASE WHEN bc.cs IS NULL THEN NULL ELSE TO_CHAR(bc.cs, 'YYYY-MM-DD') || ' → ' || TO_CHAR(bc.ce, 'YYYY-MM-DD') END";

    // Oracle treats NULL as '' in ||, so an NVL around the concatenation never fires for
    // a transaction with no card; test the card row itself.
    public static final String CARD_DIM =
            "CASE WHEN cd.last4 IS NULL THEN 'Unattributed' ELSE a.name || ' •••• ' || cd.last4 END";
    public static final String CARDHOLDER_DIM = "NVL(ch.person_name, 'Unattributed')";
    public static final String CARD_RELATIONSHIP_DIM = "NVL(ch.relationship, 'Unattributed')";

    /**
     * The description a transaction shows: the user's own, else the text it was imported with
     * (the app shows {@code description ?? sourcedDescription} everywhere), so listings, sorting
     * and description filters all work on the text people see.
     */
    public static final String DESCRIPTION = "COALESCE(t.description, t.sourced_description)";

    /** Category label of the fixed list; a transaction with none groups as "Uncategorized". */
    public static final String CATEGORY_DIM = "NVL(xc.name, 'Uncategorized')";

    /**
     * Sub-category label. The label is qualified with its category when two categories share a
     * sub-category name (the inline view in {@link #baseFrom}), so a group is never two sub-categories.
     * A categorised transaction with no sub-category is "(none)" like any other empty dimension.
     */
    public static final String SUBCATEGORY_DIM = "CASE WHEN t.subcategory_key IS NOT NULL THEN COALESCE(xs.label, t.subcategory_key)"
            + " WHEN t.category_key IS NULL THEN 'Uncategorized' ELSE '(none)' END";

    /** Every sub-category key with its grouping label (qualified by category where two share a name). */
    private static final String SUBCATEGORY_LABELS =
            "SELECT s.subcategory_key AS sk, CASE WHEN COUNT(*) OVER (PARTITION BY s.name) > 1"
            + " THEN s.name || ' (' || sc.name || ')' ELSE s.name END AS label"
            + " FROM txn_subcategories s JOIN txn_categories sc ON sc.category_key = s.category_key";

    private static final Map<String, Mapping> MAPPINGS = Map.ofEntries(
            Map.entry("amount", new Mapping(SIGNED_AMOUNT, null)),
            Map.entry("spend", new Mapping(SPEND_AMOUNT, null)),
            Map.entry("date", new Mapping("t.transaction_date", null)),
            Map.entry("type", new Mapping("t.type", null)),
            Map.entry("source", new Mapping("t.source", null)),
            Map.entry("description", new Mapping(DESCRIPTION, null)),
            Map.entry("account", new Mapping("a.name", JOIN_ACCOUNTS)),
            // Internal: the id behind the "account" field (FieldDef.idField); not a catalog field.
            Map.entry("accountId", new Mapping("t.account_id", null)),
            Map.entry("accountType", new Mapping("a.type", JOIN_ACCOUNTS)),
            // The old per-user categories, now tags (many per transaction).
            Map.entry(TAG, new Mapping("c.name", JOIN_TAGS)),
            Map.entry(CATEGORY, new Mapping(CATEGORY_DIM, JOIN_CATEGORY)),
            Map.entry(SUBCATEGORY, new Mapping(SUBCATEGORY_DIM, JOIN_SUBCATEGORY)),
            // Internal: the keys behind "category" / "subcategory" (FieldDef.idField); not catalog fields.
            Map.entry(CATEGORY_KEY, new Mapping("t.category_key", null)),
            Map.entry(SUBCATEGORY_KEY, new Mapping("t.subcategory_key", null)),
            Map.entry(CATEGORY_LABEL, new Mapping("xc.name", JOIN_CATEGORY)),
            Map.entry(SUBCATEGORY_LABEL, new Mapping("CASE WHEN t.subcategory_key IS NOT NULL THEN COALESCE(xs.label, t.subcategory_key) END",
                    JOIN_SUBCATEGORY)),
            Map.entry("isUnderMonitoring", new Mapping("t.is_under_monitoring", null)),
            Map.entry("isExcluded", new Mapping("t.is_excluded", null)),
            Map.entry("isTransferLeg", new Mapping(IS_TRANSFER_LEG, null)),
            Map.entry("isRefundLeg", new Mapping(IS_REFUND_LEG, null)),
            Map.entry("isLendingLeg", new Mapping(IS_LENDING_LEG, null)),
            Map.entry("isLoanLeg", new Mapping(IS_LOAN_LEG, null)),
            Map.entry("isDividendLeg", new Mapping(IS_DIVIDEND_LEG, null)),
            Map.entry("linkType", new Mapping(LINK_TYPE, JOIN_LINKS)),
            Map.entry("settlementDate", new Mapping("t.settlement_date", null)),
            Map.entry("billingCycle", new Mapping(BILLING_CYCLE_DIM, JOIN_BILLING_CYCLES)),
            Map.entry("reviewType", new Mapping("t.review_type", null)),
            Map.entry("mcc", new Mapping("t.mcc", null)),
            Map.entry("channel", new Mapping("t.channel", null)),
            Map.entry("isEmi", new Mapping("NVL(t.is_emi, 0)", null)),
            Map.entry("isInternational", new Mapping("NVL(t.is_international, 0)", null)),
            Map.entry("instantDiscount", new Mapping("t.instant_discount", null)),
            Map.entry("convenienceFee", new Mapping("t.convenience_fee", null)),
            Map.entry("card", new Mapping(CARD_DIM, JOIN_CARDS)),
            Map.entry("cardholder", new Mapping(CARDHOLDER_DIM, JOIN_CARDS)),
            Map.entry("cardRelationship", new Mapping(CARD_RELATIONSHIP_DIM, JOIN_CARDS)));

    private final BillingCycleService billingCycleService;

    public TransactionQueryBuilder(Map<String, FieldDef> fieldsMap, DateRangeResolver dateRangeResolver, SqlPredicates sqlPredicates) {
        this(fieldsMap, dateRangeResolver, sqlPredicates, null);
    }

    public TransactionQueryBuilder(Map<String, FieldDef> fieldsMap, DateRangeResolver dateRangeResolver,
                                   SqlPredicates sqlPredicates, BillingCycleService billingCycleService) {
        super(MAPPINGS, fieldsMap, sqlPredicates, dateRangeResolver);
        this.billingCycleService = billingCycleService;
    }

    /**
     * Tag filters (by name): a transaction can have several tags, so it matches through a
     * semi-join over its tags ({@link SqlPredicates#category}) instead of the tags join,
     * which would repeat it once per matching tag (a KPI double counted it and listings repeated
     * its id). When the query also groups by tag (the join is already recorded: executors
     * resolve grouping expressions before the WHERE), the joined tag must match as well, so the
     * groups are the filtered tags only.
     *
     * <p>Category and sub-category filters hold keys ({@code food}, {@code food.delivery}) and match the
     * transaction's stored keys; a category key therefore includes every one of its sub-categories
     * (a transaction stores its parent category beside its sub-category).
     *
     * <p>Billing-cycle operators: each account keeps its own cycle window (a credit card's from its
     * statements, any other account's calendar month), matched on {@link #EFFECTIVE_DATE}
     * whichever date field carries the operator. Bound as one (account AND range) disjunct per
     * account; the validator limits the report to one account.
     */
    @Override
    protected String specialPredicate(FilterClause filter, UUID userId, Map<String, Object> params, Set<String> joins, int idx) {
        if (ACCOUNT.equals(filter.field())) {
            return accountPredicate(filter, params, joins, "f" + idx);
        }
        if (CATEGORY.equals(filter.field()) || SUBCATEGORY.equals(filter.field())) {
            return categoryPredicate(CATEGORY.equals(filter.field()), filter, params, "f" + idx);
        }
        if (TAG.equals(filter.field())) {
            String p = "f" + idx;
            String semiJoin = sqlPredicates.category(filter.operator(), filter.value(), params, p, idExpression());
            if (!joins.contains(JOIN_TAGS)) {
                return semiJoin;
            }
            String joinedRow = sqlPredicates.build(FieldType.ENUM, MAPPINGS.get(TAG).expression(),
                    filter.operator(), filter.value(), params, p + "r");
            return "(" + semiJoin + " AND " + joinedRow + ")";
        }
        FieldDef field = catalogFields.get(filter.field());
        if (field == null || field.type() != FieldType.DATE || !CycleOperators.isCycle(filter.operator())) {
            return null;
        }
        CycleWindows windows = requireCycles().windows(userId, CycleOperators.cyclesAgo(filter), AppTime.today());
        List<String> parts = new ArrayList<>();
        int i = 0;
        for (Map.Entry<UUID, Cycle> e : windows.byAccount().entrySet()) {
            String p = "f" + idx + "_c" + i++;
            params.put(p + "a", e.getKey().toString());
            params.put(p + "s", e.getValue().start());
            params.put(p + "e", e.getValue().end());
            parts.add("(t.account_id = :" + p + "a AND " + EFFECTIVE_DATE + " BETWEEN :" + p + "s AND :" + p + "e)");
        }
        return parts.isEmpty() ? "1 = 0" : "(" + String.join(" OR ", parts) + ")";
    }

    /**
     * Category / sub-category filters take a key (the dropdown value) or the label exactly as the grouping
     * prints it, because a drill from a chart bar filters on the bucket's label: "Food", a qualified sub
     * label such as "Kids (Shopping)", "Uncategorized" (no category) and, on sub-categories, "(none)"
     * (a category without a sub-category). Labels resolve to keys through the seeded global tables
     * (no user data), and the query is still scoped to the user by the caller's user predicate. As with any
     * enum, a negated operator keeps the rows that have no value.
     */
    private String categoryPredicate(boolean category, FilterClause filter, Map<String, Object> params, String p) {
        String op = filter.operator();
        boolean negated = "is_not".equals(op) || "not_in".equals(op);
        if (!negated && !"is".equals(op) && !"in".equals(op)) {
            throw new IllegalArgumentException("Unsupported " + (category ? "category" : "subcategory") + " operator: " + op);
        }
        List<String> values = filter.value().isArray() ? SqlPredicates.textList(filter.value())
                : new ArrayList<>(List.of(filter.value().asText()));
        params.put(p, values);
        String key = category ? "t.category_key" : "t.subcategory_key";
        String labelled = category
                ? "SELECT xk.category_key FROM txn_categories xk WHERE xk.name IN (:" + p + ")"
                : "SELECT xk.sk FROM (" + SUBCATEGORY_LABELS + ") xk WHERE xk.label IN (:" + p + ")";
        List<String> any = new ArrayList<>();
        any.add(key + " IN (:" + p + ")");
        any.add(key + " IN (" + labelled + ")");
        if (values.contains("Uncategorized")) {
            any.add("t.category_key IS NULL");
        }
        if (!category && values.contains("(none)")) {
            any.add("(t.category_key IS NOT NULL AND t.subcategory_key IS NULL)");
        }
        // CASE turns "unknown" (a NULL key) into a plain no, so the negation keeps those rows.
        String match = "(CASE WHEN " + String.join(" OR ", any) + " THEN 1 ELSE 0 END) = 1";
        return negated ? "NOT (" + match + ")" : match;
    }

    /**
     * Account filters hold account names (the filter dropdown) or account ids (links built by the
     * app, e.g. the emergency fund drill, where names may repeat). A value that parses as a UUID
     * matches the transaction's account id, any other value the account's name; a positive operator
     * matches either, a negated one excludes both. Null when every value is a name (the standard
     * name predicate applies).
     */
    private String accountPredicate(FilterClause filter, Map<String, Object> params, Set<String> joins, String p) {
        String op = filter.operator();
        if (filter.value() == null) {
            return null;
        }
        List<String> values = new ArrayList<>();
        if (filter.value().isArray()) {
            filter.value().forEach(v -> values.add(v.asText()));
        } else {
            values.add(filter.value().asText());
        }
        List<String> ids = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (String v : values) {
            if (isUuid(v)) {
                ids.add(v.toLowerCase());
            } else {
                names.add(v);
            }
        }
        if (ids.isEmpty()) {
            return null;
        }
        boolean negated = "is_not".equals(op) || "not_in".equals(op);
        if (!negated && !"is".equals(op) && !"in".equals(op)) {
            return null;
        }
        params.put(p + "i", ids);
        // A missing account is never X, so NULL rows pass the negated operators (as the name predicate does).
        String byId = negated ? "(t.account_id NOT IN (:" + p + "i) OR t.account_id IS NULL)"
                : "t.account_id IN (:" + p + "i)";
        if (names.isEmpty()) {
            return byId;
        }
        params.put(p + "n", names);
        String name = expression(ACCOUNT, joins);
        String byName = negated
                ? "(" + name + " NOT IN (:" + p + "n) OR " + name + " IS NULL)"
                : name + " IN (:" + p + "n)";
        return "(" + byId + (negated ? " AND " : " OR ") + byName + ")";
    }

    private static boolean isUuid(String value) {
        if (value == null || value.length() != 36) {
            return false;
        }
        try {
            UUID.fromString(value);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private BillingCycleService requireCycles() {
        if (billingCycleService == null) {
            throw new IllegalStateException("Billing cycles are not available on this query builder");
        }
        return billingCycleService;
    }

    @Override
    public String idExpression() {
        return "t.id";
    }

    @Override
    protected String userScopePredicate(Map<String, Object> params, UUID userId) {
        params.put("userId", userId.toString());
        return "t.user_id = :userId";
    }

    @Override
    public String fromClause(Set<String> joins) {
        if (joins.contains(JOIN_BILLING_CYCLES)) {
            throw new IllegalStateException("The billing cycle join binds parameters; use fromClause(joins, params, userId)");
        }
        return baseFrom(joins);
    }

    /** Adds the user's card cycle table (bound rows) when the billing-cycle dimension is used. */
    @Override
    public String fromClause(Set<String> joins, Map<String, Object> params, UUID userId) {
        String from = baseFrom(joins);
        if (!joins.contains(JOIN_BILLING_CYCLES)) {
            return from;
        }
        List<BillingCycleService.AccountCycle> rows = requireCycles().cycleTable(userId, AppTime.today());
        List<String> selects = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            BillingCycleService.AccountCycle row = rows.get(i);
            params.put("bc" + i + "a", row.accountId().toString());
            params.put("bc" + i + "s", row.start());
            params.put("bc" + i + "e", row.end());
            selects.add("SELECT :bc" + i + "a AS account_id, :bc" + i + "s AS cs, :bc" + i + "e AS ce FROM dual");
        }
        String table = selects.isEmpty()
                ? "SELECT CAST(NULL AS VARCHAR2(36)) AS account_id, CAST(NULL AS DATE) AS cs, CAST(NULL AS DATE) AS ce FROM dual WHERE 1 = 0"
                : String.join(" UNION ALL ", selects);
        return from + " LEFT JOIN (" + table + ") bc ON bc.account_id = t.account_id"
                + " AND " + EFFECTIVE_DATE + " BETWEEN bc.cs AND bc.ce";
    }

    private String baseFrom(Set<String> joins) {
        StringBuilder sb = new StringBuilder(" FROM transactions t");
        if (joins.contains(JOIN_ACCOUNTS) || joins.contains(JOIN_CARDS)) {
            sb.append(" LEFT JOIN accounts a ON a.id = t.account_id");
        }
        if (joins.contains(JOIN_CARDS)) {
            // "cd", not "c": the tags join below also needs an alias and "c" is taken there.
            sb.append(" LEFT JOIN cards cd ON cd.id = t.card_id LEFT JOIN cardholders ch ON ch.id = cd.cardholder_id");
        }
        if (joins.contains(JOIN_CATEGORY)) {
            sb.append(" LEFT JOIN txn_categories xc ON xc.category_key = t.category_key");
        }
        if (joins.contains(JOIN_SUBCATEGORY)) {
            sb.append(" LEFT JOIN (").append(SUBCATEGORY_LABELS).append(") xs ON xs.sk = t.subcategory_key");
        }
        if (joins.contains(JOIN_TAGS)) {
            sb.append(" LEFT JOIN transaction_tags tc ON tc.transaction_id = t.id")
              .append(" LEFT JOIN tags c ON c.id = tc.tag_id");
        }
        if (joins.contains(JOIN_LINKS)) {
            // One link per transaction is the product rule; MIN keeps the join 1:1 regardless.
            sb.append(" LEFT JOIN (SELECT m.transaction_id, MIN(l.type) AS link_type")
              .append(" FROM transaction_link_members m JOIN transaction_links l ON l.id = m.link_id")
              .append(" GROUP BY m.transaction_id) lk ON lk.transaction_id = t.id");
        }
        return sb.toString();
    }
}
