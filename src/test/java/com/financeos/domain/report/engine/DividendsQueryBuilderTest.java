package com.financeos.domain.report.engine;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.node.TextNode;
import com.financeos.domain.report.datasource.DatasourceCatalog;
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

class DividendsQueryBuilderTest {

    private DividendsQueryBuilder queryBuilder;

    @BeforeEach
    void setUp() {
        DatasourceCatalog catalog = new DatasourceCatalog();
        DateRangeResolver dateRangeResolver = new DateRangeResolver(4);
        SqlPredicates sqlPredicates = new SqlPredicates(dateRangeResolver);
        DividendsDatasource ds = new DividendsDatasource(sqlPredicates, dateRangeResolver);
        queryBuilder = (DividendsQueryBuilder) ds.queryBuilder();
    }

    @Test
    void expressionAndJoinRecording() {
        Set<String> joins = new HashSet<>();
        String amountExpr = queryBuilder.expression("amount", joins);
        assertEquals("d.amount", amountExpr);
        assertTrue(joins.isEmpty());

        String instExpr = queryBuilder.expression("instrument", joins);
        assertEquals("ins.name", instExpr);
        assertTrue(joins.contains(DividendsQueryBuilder.JOIN_INSTRUMENTS));
        assertTrue(joins.contains(DividendsQueryBuilder.JOIN_HOLDINGS));
    }

    @Test
    void buildWherePinsUserId() {
        UUID userId = UUID.randomUUID();
        Map<String, Object> params = new HashMap<>();
        Set<String> joins = new HashSet<>();
        List<FilterClause> filters = List.of(
                new FilterClause("type", "is", TextNode.valueOf("dividend"))
        );

        String where = queryBuilder.buildWhere(filters, userId, params, joins);

        assertTrue(where.startsWith(" WHERE d.user_id = :userId"));
        assertTrue(where.contains("d.type = :f0"));
        assertEquals(userId.toString(), params.get("userId"));
        assertEquals("dividend", params.get("f0"));
    }
}
