package com.financeos.domain.report.engine;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.financeos.domain.report.datasource.DatasourceCatalog.FieldDef;
import com.financeos.domain.report.datasource.FieldRole;
import com.financeos.domain.report.datasource.FieldType;
import com.financeos.domain.report.datasource.impl.DividendsDatasource;
import com.financeos.domain.report.datasource.impl.DividendsDatasource.DividendsQueryBuilder;
import com.financeos.domain.report.definition.FilterClause;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** Receipt fields added to the dividends datasource (V89 columns). */
class DividendsQueryBuilderReceiptTest {

    private DividendsDatasource datasource;
    private DividendsQueryBuilder queryBuilder;

    @BeforeEach
    void setUp() {
        DateRangeResolver dateRangeResolver = new DateRangeResolver(4);
        SqlPredicates sqlPredicates = new SqlPredicates(dateRangeResolver);
        datasource = new DividendsDatasource(sqlPredicates, dateRangeResolver);
        queryBuilder = (DividendsQueryBuilder) datasource.queryBuilder();
    }

    @Test
    void catalogExposesTheReceiptFields() {
        Map<String, FieldDef> fields = datasource.fields().stream().collect(Collectors.toMap(FieldDef::name, f -> f));
        assertEquals(FieldType.ENUM, fields.get("receipt").type());
        assertEquals(FieldRole.DIMENSION, fields.get("receipt").role());
        assertEquals(List.of("received", "received_untracked", "not_received", "pending"), fields.get("receipt").values());
        assertEquals(FieldType.BOOLEAN, fields.get("isLinked").type());
        assertEquals(FieldRole.FILTER, fields.get("isLinked").role());
        assertEquals(FieldRole.MEASURE, fields.get("receivedAmount").role());
        assertEquals("currency", fields.get("receivedAmount").format());
        assertEquals(FieldType.DATE, fields.get("receivedDate").type());
    }

    @Test
    void receiptAndIsLinkedNeedNoJoin() {
        Set<String> joins = new HashSet<>();
        assertEquals(DividendsQueryBuilder.RECEIPT_EXPR, queryBuilder.expression("receipt", joins));
        assertEquals(DividendsQueryBuilder.IS_LINKED_EXPR, queryBuilder.expression("isLinked", joins));
        assertTrue(joins.isEmpty());
    }

    @Test
    void receivedFieldsJoinTheTransactionWithoutDraggingHoldingsIn() {
        Set<String> joins = new HashSet<>();
        assertEquals("tx.amount", queryBuilder.expression("receivedAmount", joins));
        assertEquals("tx.transaction_date", queryBuilder.expression("receivedDate", joins));
        assertEquals(Set.of(DividendsQueryBuilder.JOIN_TRANSACTIONS), joins);

        String from = queryBuilder.fromClause(joins);
        assertTrue(from.contains("LEFT JOIN transactions tx ON tx.id = d.transaction_id"));
        assertFalse(from.contains("JOIN holdings"));
    }

    @Test
    void isLinkedFilterBindsOneAndZero() {
        UUID userId = UUID.randomUUID();
        Map<String, Object> params = new HashMap<>();
        Set<String> joins = new HashSet<>();
        String where = queryBuilder.buildWhere(List.of(new FilterClause("isLinked", "is", BooleanNode.TRUE)), userId, params, joins);
        assertTrue(where.contains(DividendsQueryBuilder.IS_LINKED_EXPR + " = :f0"));
        assertEquals(1, params.get("f0"));
    }

    @Test
    void receiptFilterComparesTheCaseExpression() {
        UUID userId = UUID.randomUUID();
        Map<String, Object> params = new HashMap<>();
        Set<String> joins = new HashSet<>();
        String where = queryBuilder.buildWhere(List.of(new FilterClause("receipt", "is", TextNode.valueOf("pending"))), userId, params, joins);
        assertTrue(where.contains(DividendsQueryBuilder.RECEIPT_EXPR + " = :f0"));
        assertEquals("pending", params.get("f0"));
    }
}
