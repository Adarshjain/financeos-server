package com.financeos.domain.report.engine;

import static org.junit.jupiter.api.Assertions.*;

import com.financeos.domain.report.datasource.impl.TransactionsDatasource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

class TransactionQueryBuilderTest {

    private TransactionQueryBuilder queryBuilder;

    @BeforeEach
    void setUp() {
        DateRangeResolver dateRangeResolver = new DateRangeResolver(4);
        SqlPredicates sqlPredicates = new SqlPredicates(dateRangeResolver);
        TransactionsDatasource ds = new TransactionsDatasource(sqlPredicates, dateRangeResolver);
        queryBuilder = (TransactionQueryBuilder) ds.queryBuilder();
    }

    @Test
    void newFieldMappingsAndExpressions() {
        Set<String> joins = new HashSet<>();

        assertEquals("t.mcc", queryBuilder.expression("mcc", joins));
        assertTrue(joins.isEmpty());

        assertEquals("t.channel", queryBuilder.expression("channel", joins));
        assertTrue(joins.isEmpty());

        assertEquals("NVL(t.is_emi, 0)", queryBuilder.expression("isEmi", joins));
        assertTrue(joins.isEmpty());

        assertEquals("NVL(t.is_international, 0)", queryBuilder.expression("isInternational", joins));
        assertTrue(joins.isEmpty());

        assertEquals("t.instant_discount", queryBuilder.expression("instantDiscount", joins));
        assertTrue(joins.isEmpty());

        assertEquals("t.convenience_fee", queryBuilder.expression("convenienceFee", joins));
        assertTrue(joins.isEmpty());
    }

    @Test
    void existingFieldMappingsAndJoins() {
        Set<String> joins = new HashSet<>();

        assertEquals(TransactionQueryBuilder.SIGNED_AMOUNT, queryBuilder.expression("amount", joins));
        assertTrue(joins.isEmpty());

        assertEquals("a.name", queryBuilder.expression("account", joins));
        assertTrue(joins.contains(TransactionQueryBuilder.JOIN_ACCOUNTS));

        assertEquals("c.name", queryBuilder.expression("category", joins));
        assertTrue(joins.contains(TransactionQueryBuilder.JOIN_CATEGORIES));

        String from = queryBuilder.fromClause(joins);
        assertTrue(from.contains("FROM transactions t"));
        assertTrue(from.contains("LEFT JOIN accounts a ON a.id = t.account_id"));
        assertTrue(from.contains("LEFT JOIN transaction_categories tc ON tc.transaction_id = t.id"));
        assertTrue(from.contains("LEFT JOIN categories c ON c.id = tc.category_id"));
    }
}
