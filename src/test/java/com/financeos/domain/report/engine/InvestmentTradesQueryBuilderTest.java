package com.financeos.domain.report.engine;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.node.TextNode;
import com.financeos.domain.report.datasource.DatasourceCatalog;
import com.financeos.domain.report.datasource.impl.InvestmentTradesDatasource;
import com.financeos.domain.report.datasource.impl.InvestmentTradesDatasource.InvestmentTradesQueryBuilder;
import com.financeos.domain.report.definition.FilterClause;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

class InvestmentTradesQueryBuilderTest {

    private InvestmentTradesQueryBuilder queryBuilder;

    @BeforeEach
    void setUp() {
        DatasourceCatalog catalog = new DatasourceCatalog();
        DateRangeResolver dateRangeResolver = new DateRangeResolver(4);
        SqlPredicates sqlPredicates = new SqlPredicates(dateRangeResolver);
        InvestmentTradesDatasource ds = new InvestmentTradesDatasource(sqlPredicates, dateRangeResolver);
        queryBuilder = (InvestmentTradesQueryBuilder) ds.queryBuilder();
    }

    @Test
    void expressionAndJoinRecording() {
        Set<String> joins = new HashSet<>();
        String tradeValueExpr = queryBuilder.expression("tradeValue", joins);
        assertEquals("(it.quantity * it.price)", tradeValueExpr);
        assertTrue(joins.isEmpty());

        String brokerExpr = queryBuilder.expression("broker", joins);
        assertEquals("acc.name", brokerExpr);
        assertTrue(joins.contains(InvestmentTradesQueryBuilder.JOIN_ACCOUNTS));
        assertTrue(joins.contains(InvestmentTradesQueryBuilder.JOIN_HOLDINGS));

        String instExpr = queryBuilder.expression("instrument", joins);
        assertEquals("ins.name", instExpr);
        assertTrue(joins.contains(InvestmentTradesQueryBuilder.JOIN_INSTRUMENTS));
    }

    @Test
    void composedJoinsEmitsHoldingsOnceInFromClause() {
        Set<String> joins = Set.of(
                InvestmentTradesQueryBuilder.JOIN_HOLDINGS,
                InvestmentTradesQueryBuilder.JOIN_INSTRUMENTS,
                InvestmentTradesQueryBuilder.JOIN_ACCOUNTS
        );
        String from = queryBuilder.fromClause(joins);

        // Verify FROM contains investment_transactions it and each join table
        assertTrue(from.contains("FROM investment_transactions it"));
        assertTrue(from.contains("JOIN holdings h ON h.id = it.holding_id"));
        assertTrue(from.contains("JOIN instruments ins ON ins.id = h.instrument_id"));
        assertTrue(from.contains("JOIN accounts acc ON acc.id = h.broker_account_id"));

        // Count occurrences of "JOIN holdings h"
        int firstIndex = from.indexOf("JOIN holdings h");
        int lastIndex = from.lastIndexOf("JOIN holdings h");
        assertEquals(firstIndex, lastIndex, "holdings join must appear exactly once");
    }

    @Test
    void buildWherePinsUserIdAndBindsParams() {
        UUID userId = UUID.randomUUID();
        Map<String, Object> params = new HashMap<>();
        Set<String> joins = new HashSet<>();
        List<FilterClause> filters = List.of(
                new FilterClause("broker", "is", TextNode.valueOf("Zerodha"))
        );

        String where = queryBuilder.buildWhere(filters, userId, params, joins);

        assertTrue(where.startsWith(" WHERE it.user_id = :userId"));
        assertTrue(where.contains("acc.name = :f0"));
        assertEquals(userId.toString(), params.get("userId"));
        assertEquals("Zerodha", params.get("f0"));
        assertTrue(joins.contains(InvestmentTradesQueryBuilder.JOIN_ACCOUNTS));
    }
}
