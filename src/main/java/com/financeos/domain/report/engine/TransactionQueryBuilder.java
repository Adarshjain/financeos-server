package com.financeos.domain.report.engine;

import com.financeos.domain.report.datasource.DatasourceCatalog.FieldDef;
import com.financeos.domain.report.definition.FilterClause;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Builds native-SQL fragments over the {@code transactions} data source for the report engine.
 */
public class TransactionQueryBuilder extends AbstractReportQueryBuilder {

    public static final String SIGNED_AMOUNT = "(CASE WHEN t.type = 'CREDIT' THEN t.amount ELSE -t.amount END)";

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

    public static final String JOIN_ACCOUNTS = "ACCOUNTS";
    public static final String JOIN_CATEGORIES = "CATEGORIES";

    private static final Map<String, Mapping> MAPPINGS = Map.ofEntries(
            Map.entry("amount", new Mapping(SIGNED_AMOUNT, null)),
            Map.entry("date", new Mapping("t.transaction_date", null)),
            Map.entry("type", new Mapping("t.type", null)),
            Map.entry("source", new Mapping("t.source", null)),
            Map.entry("description", new Mapping("t.description", null)),
            Map.entry("account", new Mapping("a.name", JOIN_ACCOUNTS)),
            Map.entry("accountType", new Mapping("a.type", JOIN_ACCOUNTS)),
            Map.entry("category", new Mapping("c.name", JOIN_CATEGORIES)),
            Map.entry("isUnderMonitoring", new Mapping("t.is_under_monitoring", null)),
            Map.entry("isExcluded", new Mapping("t.is_excluded", null)),
            Map.entry("isTransferLeg", new Mapping(IS_TRANSFER_LEG, null)),
            Map.entry("isRefundLeg", new Mapping(IS_REFUND_LEG, null)),
            Map.entry("linkType", new Mapping(LINK_TYPE, null)),
            Map.entry("settlementDate", new Mapping("t.settlement_date", null)),
            Map.entry("reviewType", new Mapping("t.review_type", null)),
            Map.entry("mcc", new Mapping("t.mcc", null)),
            Map.entry("channel", new Mapping("t.channel", null)),
            Map.entry("isEmi", new Mapping("NVL(t.is_emi, 0)", null)),
            Map.entry("isInternational", new Mapping("NVL(t.is_international, 0)", null)),
            Map.entry("instantDiscount", new Mapping("t.instant_discount", null)),
            Map.entry("convenienceFee", new Mapping("t.convenience_fee", null)));

    public TransactionQueryBuilder(Map<String, FieldDef> fieldsMap, DateRangeResolver dateRangeResolver, SqlPredicates sqlPredicates) {
        super(MAPPINGS, fieldsMap, sqlPredicates, dateRangeResolver);
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
        StringBuilder sb = new StringBuilder(" FROM transactions t");
        if (joins.contains(JOIN_ACCOUNTS)) {
            sb.append(" LEFT JOIN accounts a ON a.id = t.account_id");
        }
        if (joins.contains(JOIN_CATEGORIES)) {
            sb.append(" LEFT JOIN transaction_categories tc ON tc.transaction_id = t.id")
              .append(" LEFT JOIN categories c ON c.id = tc.category_id");
        }
        return sb.toString();
    }

    @Override
    protected String specialPredicate(FilterClause filter, Map<String, Object> params, Set<String> joins, int idx) {
        if ("category".equals(filter.field())) {
            return sqlPredicates.category(filter.operator(), filter.value(), params, "f" + idx, "t.id");
        }
        return null;
    }
}
