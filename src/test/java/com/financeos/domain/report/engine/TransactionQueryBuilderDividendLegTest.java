package com.financeos.domain.report.engine;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.node.BooleanNode;
import com.financeos.domain.report.datasource.DatasourceCatalog.FieldDef;
import com.financeos.domain.report.datasource.FieldRole;
import com.financeos.domain.report.datasource.FieldType;
import com.financeos.domain.report.datasource.impl.TransactionsDatasource;
import com.financeos.domain.report.definition.FilterClause;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Derived "is dividend leg" filter on the transactions datasource (sibling of isLendingLeg / isLoanLeg). */
class TransactionQueryBuilderDividendLegTest {

    private TransactionsDatasource datasource;
    private TransactionQueryBuilder queryBuilder;

    @BeforeEach
    void setUp() {
        DateRangeResolver dateRangeResolver = new DateRangeResolver(4);
        SqlPredicates sqlPredicates = new SqlPredicates(dateRangeResolver);
        datasource = new TransactionsDatasource(sqlPredicates, dateRangeResolver);
        queryBuilder = (TransactionQueryBuilder) datasource.queryBuilder();
    }

    @Test
    void isDividendLeg_isAnExistsSubqueryOnDividendsWithNoJoin() {
        Set<String> joins = new HashSet<>();
        String expr = queryBuilder.expression("isDividendLeg", joins);
        assertEquals(TransactionQueryBuilder.IS_DIVIDEND_LEG, expr);
        assertTrue(expr.contains("FROM dividends x WHERE x.transaction_id = t.id"));
        assertTrue(joins.isEmpty());
    }

    @Test
    void isDividendLeg_isABooleanFilterFieldInTheCatalog() {
        FieldDef field = datasource.fields().stream().filter(f -> f.name().equals("isDividendLeg")).findFirst().orElseThrow();
        assertEquals(FieldType.BOOLEAN, field.type());
        assertEquals(FieldRole.FILTER, field.role());
        assertEquals("Is dividend leg", field.label());
    }

    @Test
    void isDividendLeg_filterBindsZeroForFalse() {
        UUID userId = UUID.randomUUID();
        Map<String, Object> params = new HashMap<>();
        Set<String> joins = new HashSet<>();
        String where = queryBuilder.buildWhere(List.of(new FilterClause("isDividendLeg", "is", BooleanNode.FALSE)), userId, params, joins);
        assertTrue(where.contains(TransactionQueryBuilder.IS_DIVIDEND_LEG + " = :f0"));
        assertEquals(0, params.get("f0"));
    }
}
