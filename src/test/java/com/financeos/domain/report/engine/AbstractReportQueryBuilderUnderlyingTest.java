package com.financeos.domain.report.engine;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.financeos.domain.report.datasource.impl.DividendsDatasource;
import com.financeos.domain.report.datasource.impl.TransactionsDatasource;
import com.financeos.domain.report.definition.FilterClause;
import com.financeos.domain.report.underlying.UnderlyingOperators;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The SQL query builders render the KPI underlying-data clauses over the field's own expression. */
class AbstractReportQueryBuilderUnderlyingTest {

    private final DateRangeResolver resolver = new DateRangeResolver(4);
    private final SqlPredicates predicates = new SqlPredicates(resolver);
    private final UUID userId = UUID.randomUUID();

    @Test
    void presentIsAnIsNotNullOnTheMeasureExpression() {
        ReportQueryBuilder qb = new TransactionsDatasource(predicates, resolver).queryBuilder();
        Map<String, Object> params = new HashMap<>();

        String where = qb.buildWhere(List.of(new FilterClause("amount", UnderlyingOperators.PRESENT, null)),
                userId, params, new HashSet<>());

        assertEquals(" WHERE t.user_id = :userId AND " + TransactionQueryBuilder.SIGNED_AMOUNT + " IS NOT NULL", where);
        assertEquals(Map.of("userId", userId.toString()), params);
    }

    @Test
    void equalToBindsTheExactDecimal() {
        ReportQueryBuilder qb = new TransactionsDatasource(predicates, resolver).queryBuilder();
        Map<String, Object> params = new HashMap<>();
        FilterClause equal = new FilterClause("spend", UnderlyingOperators.EQUAL_TO,
                JsonNodeFactory.instance.numberNode(new BigDecimal("-1234.56")));

        String where = qb.buildWhere(List.of(new FilterClause("type", "is", JsonNodeFactory.instance.textNode("DEBIT")), equal),
                userId, params, new HashSet<>());

        assertTrue(where.endsWith(" AND " + TransactionQueryBuilder.SPEND_AMOUNT + " = :f1"), where);
        assertEquals(0, new BigDecimal("-1234.56").compareTo((BigDecimal) params.get("f1")));
    }

    @Test
    void aJoinedMeasureRecordsItsJoin() {
        ReportQueryBuilder qb = new DividendsDatasource(predicates, resolver).queryBuilder();
        Set<String> joins = new HashSet<>();

        String where = qb.buildWhere(List.of(new FilterClause("receivedAmount", UnderlyingOperators.PRESENT, null)),
                userId, new HashMap<>(), joins);

        assertTrue(where.endsWith(" AND tx.amount IS NOT NULL"), where);
        assertEquals(Set.of(DividendsDatasource.DividendsQueryBuilder.JOIN_TRANSACTIONS), joins);
    }
}
