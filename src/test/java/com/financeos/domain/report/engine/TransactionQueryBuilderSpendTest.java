package com.financeos.domain.report.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.IntNode;
import com.financeos.domain.report.datasource.impl.TransactionsDatasource;
import com.financeos.domain.report.definition.FilterClause;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** "spend" is the spend-positive view of the signed amount: debits positive, credits negative. */
class TransactionQueryBuilderSpendTest {

    private TransactionQueryBuilder queryBuilder;

    @BeforeEach
    void setUp() {
        DateRangeResolver dateRangeResolver = new DateRangeResolver(4);
        SqlPredicates sqlPredicates = new SqlPredicates(dateRangeResolver);
        queryBuilder = (TransactionQueryBuilder) new TransactionsDatasource(sqlPredicates, dateRangeResolver).queryBuilder();
    }

    @Test
    void spendMapsToTheDebitPositiveExpressionWithNoJoin() {
        Set<String> joins = new HashSet<>();
        assertEquals("(CASE WHEN t.type = 'DEBIT' THEN t.amount ELSE -t.amount END)",
                queryBuilder.expression("spend", joins));
        assertEquals(TransactionQueryBuilder.SPEND_AMOUNT, queryBuilder.expression("spend", joins));
        assertTrue(joins.isEmpty());
    }

    @Test
    void spendIsTheNegationOfTheSignedAmount() {
        // amount keeps its credit-positive sign; spend flips which side is positive
        assertEquals(TransactionQueryBuilder.SIGNED_AMOUNT, queryBuilder.expression("amount", new HashSet<>()));
        assertNotEquals(TransactionQueryBuilder.SIGNED_AMOUNT, TransactionQueryBuilder.SPEND_AMOUNT);
        assertTrue(TransactionQueryBuilder.SIGNED_AMOUNT.contains("WHEN t.type = 'CREDIT' THEN t.amount"));
        assertTrue(TransactionQueryBuilder.SPEND_AMOUNT.contains("WHEN t.type = 'DEBIT' THEN t.amount"));
    }

    @Test
    void filteringOnSpendComparesTheSpendExpression() {
        Map<String, Object> params = new HashMap<>();
        Set<String> joins = new HashSet<>();
        String where = queryBuilder.buildWhere(
                List.of(new FilterClause("spend", "greater_than", IntNode.valueOf(500))),
                UUID.randomUUID(), params, joins);
        assertTrue(where.contains(TransactionQueryBuilder.SPEND_AMOUNT + " > :f0"), where);
        assertEquals(0, new BigDecimal("500").compareTo(new BigDecimal(params.get("f0").toString())));
        assertTrue(joins.isEmpty());
    }
}
