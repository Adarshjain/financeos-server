package com.financeos.domain.report.engine;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.node.TextNode;
import com.financeos.domain.report.datasource.DatasourceCatalog;
import com.financeos.domain.report.datasource.impl.FnoTradesDatasource;
import com.financeos.domain.report.datasource.impl.FnoTradesDatasource.FnoTradesQueryBuilder;
import com.financeos.domain.report.definition.FilterClause;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

class FnoTradesQueryBuilderTest {

    private FnoTradesQueryBuilder queryBuilder;

    @BeforeEach
    void setUp() {
        DatasourceCatalog catalog = new DatasourceCatalog();
        DateRangeResolver dateRangeResolver = new DateRangeResolver(4);
        SqlPredicates sqlPredicates = new SqlPredicates(dateRangeResolver);
        FnoTradesDatasource ds = new FnoTradesDatasource(sqlPredicates, dateRangeResolver);
        queryBuilder = (FnoTradesQueryBuilder) ds.queryBuilder();
    }

    @Test
    void expressionAndJoinRecording() {
        Set<String> joins = new HashSet<>();
        String pnlExpr = queryBuilder.expression("realizedPnl", joins);
        assertEquals("f.realized_pnl", pnlExpr);
        assertTrue(joins.isEmpty());

        String brokerExpr = queryBuilder.expression("broker", joins);
        assertEquals("acc.name", brokerExpr);
        assertTrue(joins.contains(FnoTradesQueryBuilder.JOIN_ACCOUNTS));

        String from = queryBuilder.fromClause(joins);
        assertTrue(from.contains("FROM fno_trades f"));
        assertTrue(from.contains("JOIN accounts acc ON acc.id = f.broker_account_id"));
    }

    @Test
    void buildWherePinsUserId() {
        UUID userId = UUID.randomUUID();
        Map<String, Object> params = new HashMap<>();
        Set<String> joins = new HashSet<>();
        List<FilterClause> filters = List.of(
                new FilterClause("contractType", "is", TextNode.valueOf("option"))
        );

        String where = queryBuilder.buildWhere(filters, userId, params, joins);

        assertTrue(where.startsWith(" WHERE f.user_id = :userId"));
        assertTrue(where.contains("f.contract_type = :f0"));
        assertEquals(userId.toString(), params.get("userId"));
        assertEquals("option", params.get("f0"));
    }
}
