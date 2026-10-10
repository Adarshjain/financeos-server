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

    public static final String CATEGORY_LISTAGG =
            "(SELECT LISTAGG(cx.name, ', ') WITHIN GROUP (ORDER BY cx.name)"
            + " FROM transaction_categories tcx JOIN categories cx ON cx.id = tcx.category_id"
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

    private static final String CATEGORY = "category";
    private static final String ACCOUNT = "account";

    public static final String JOIN_ACCOUNTS = "ACCOUNTS";
    public static final String JOIN_CATEGORIES = "CATEGORIES";
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

    private static final Map<String, Mapping> MAPPINGS = Map.ofEntries(
            Map.entry("amount", new Mapping(SIGNED_AMOUNT, null)),
            Map.entry("spend", new Mapping(SPEND_AMOUNT, null)),
            Map.entry("date", new Mapping("t.transaction_date", null)),
            Map.entry("type", new Mapping("t.type", null)),
            Map.entry("source", new Mapping("t.source", null)),
            Map.entry("description", new Mapping(DESCRIPTION, null)),
            Map.entry("account", new Mapping("a.name", JOIN_ACCOUNTS)),
            Map.entry("accountType", new Mapping("a.type", JOIN_ACCOUNTS)),
            Map.entry(CATEGORY, new Mapping("c.name", JOIN_CATEGORIES)),
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
     * Category filters: a transaction can have several categories, so it matches through a
     * semi-join over its categories ({@link SqlPredicates#category}) instead of the categories join,
     * which would repeat it once per matching category (a KPI double counted it and listings repeated
     * its id). When the query also groups by category (the join is already recorded: executors
     * resolve grouping expressions before the WHERE), the joined category must match as well, so the
     * groups are the filtered categories only.
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
        if (CATEGORY.equals(filter.field())) {
            String p = "f" + idx;
            String semiJoin = sqlPredicates.category(filter.operator(), filter.value(), params, p, idExpression());
            if (!joins.contains(JOIN_CATEGORIES)) {
                return semiJoin;
            }
            String joinedRow = sqlPredicates.build(FieldType.ENUM, MAPPINGS.get(CATEGORY).expression(),
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
        String byId = negated ? "t.account_id NOT IN (:" + p + "i)" : "t.account_id IN (:" + p + "i)";
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
            // "cd", not "c": the categories join below also needs an alias and "c" is taken there.
            sb.append(" LEFT JOIN cards cd ON cd.id = t.card_id LEFT JOIN cardholders ch ON ch.id = cd.cardholder_id");
        }
        if (joins.contains(JOIN_CATEGORIES)) {
            sb.append(" LEFT JOIN transaction_categories tc ON tc.transaction_id = t.id")
              .append(" LEFT JOIN categories c ON c.id = tc.category_id");
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
