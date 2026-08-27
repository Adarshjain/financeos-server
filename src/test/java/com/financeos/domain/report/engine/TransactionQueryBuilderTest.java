package com.financeos.domain.report.engine;

import static org.junit.jupiter.api.Assertions.*;

import com.financeos.domain.report.datasource.impl.TransactionsDatasource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

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

        assertEquals("t.settlement_date", queryBuilder.expression("settlementDate", joins));
        assertTrue(joins.isEmpty());

        assertEquals("t.review_type", queryBuilder.expression("reviewType", joins));
        assertTrue(joins.isEmpty());

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
    void filterOnReviewTypeAndSettlementDate() {
        UUID userId = UUID.randomUUID();
        java.util.Map<String, Object> params = new java.util.HashMap<>();
        Set<String> joins = new HashSet<>();
        List<com.financeos.domain.report.definition.FilterClause> filters = List.of(
                new com.financeos.domain.report.definition.FilterClause(
                        "reviewType", "is", com.fasterxml.jackson.databind.node.TextNode.valueOf("MANUALLY_REVIEWED")),
                new com.financeos.domain.report.definition.FilterClause(
                        "settlementDate", "is", com.fasterxml.jackson.databind.node.TextNode.valueOf("2026-08-15"))
        );

        String where = queryBuilder.buildWhere(filters, userId, params, joins);

        assertTrue(where.startsWith(" WHERE t.user_id = :userId"));
        assertTrue(where.contains("t.review_type = :f0"));
        assertTrue(where.contains("t.settlement_date = :f1"));
        assertEquals(userId.toString(), params.get("userId"));
        assertEquals("MANUALLY_REVIEWED", params.get("f0"));
        assertEquals(java.time.LocalDate.of(2026, 8, 15), params.get("f1"));
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
