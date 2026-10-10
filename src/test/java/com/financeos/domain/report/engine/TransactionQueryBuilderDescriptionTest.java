package com.financeos.domain.report.engine;

import com.fasterxml.jackson.databind.node.TextNode;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A transaction's report "description" is the text the app shows for it: its own description,
 * else the description it was imported with — in listings, sorting and description filters alike.
 */
class TransactionQueryBuilderDescriptionTest {

    private TransactionQueryBuilder queryBuilder;

    @BeforeEach
    void setUp() {
        DateRangeResolver resolver = new DateRangeResolver(4);
        queryBuilder = (TransactionQueryBuilder) new TransactionsDatasource(new SqlPredicates(resolver), resolver)
                .queryBuilder();
    }

    @Test
    void descriptionFallsBackToTheSourcedDescriptionWithNoJoin() {
        Set<String> joins = new HashSet<>();
        assertEquals("COALESCE(t.description, t.sourced_description)", queryBuilder.expression("description", joins));
        assertEquals(TransactionQueryBuilder.DESCRIPTION, queryBuilder.expression("description", joins));
        assertTrue(joins.isEmpty());
    }

    @Test
    void everyDescriptionFilterComparesTheShownText() {
        for (String operator : List.of("exact", "starts_with", "ends_with", "contains")) {
            Map<String, Object> params = new HashMap<>();
            String where = queryBuilder.buildWhere(
                    List.of(new FilterClause("description", operator, TextNode.valueOf("Swiggy"))),
                    UUID.randomUUID(), params, new HashSet<>());
            assertTrue(where.contains(TransactionQueryBuilder.DESCRIPTION), operator + ": " + where);
            assertTrue(!where.contains("t.description ") && !where.contains("(t.description)"), operator + ": " + where);
        }
    }
}
