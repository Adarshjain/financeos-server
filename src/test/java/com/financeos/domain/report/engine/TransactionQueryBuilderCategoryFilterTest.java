package com.financeos.domain.report.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
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
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * A category filter matches a transaction through a semi-join over its categories, never through
 * the categories join (which repeats a transaction once per matching category); when the query
 * also groups by category the joined category must match too.
 */
class TransactionQueryBuilderCategoryFilterTest {

    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
    private static final String SEMI = "SELECT 1 FROM transaction_categories tcx JOIN categories cx"
            + " ON cx.id = tcx.category_id WHERE tcx.transaction_id = t.id AND ";

    private final UUID userId = UUID.randomUUID();
    private TransactionQueryBuilder queryBuilder;

    @BeforeEach
    void setUp() {
        DateRangeResolver dateRangeResolver = new DateRangeResolver(4);
        queryBuilder = (TransactionQueryBuilder) new TransactionsDatasource(new SqlPredicates(dateRangeResolver),
                dateRangeResolver).queryBuilder();
    }

    private String where(FilterClause filter, Set<String> joins, Map<String, Object> params) {
        return queryBuilder.buildWhere(List.of(filter), userId, params, joins);
    }

    private static JsonNode array(String... values) {
        var array = JSON.arrayNode();
        for (String v : values) {
            array.add(v);
        }
        return array;
    }

    @Test
    void isMatchesThroughExistsWithoutTheCategoriesJoin() {
        Set<String> joins = new HashSet<>();
        Map<String, Object> params = new HashMap<>();

        String where = where(new FilterClause("category", "is", JSON.textNode("Food")), joins, params);

        assertEquals(" WHERE t.user_id = :userId AND EXISTS (" + SEMI + "cx.name = :f0)", where);
        assertEquals("Food", params.get("f0"));
        assertFalse(joins.contains(TransactionQueryBuilder.JOIN_CATEGORIES));
        assertFalse(queryBuilder.fromClause(joins).contains("categories"));
    }

    @Test
    void isNotMatchesThroughNotExists() {
        Map<String, Object> params = new HashMap<>();

        String where = where(new FilterClause("category", "is_not", JSON.textNode("Food")), new HashSet<>(), params);

        assertEquals(" WHERE t.user_id = :userId AND NOT EXISTS (" + SEMI + "cx.name = :f0)", where);
        assertEquals("Food", params.get("f0"));
    }

    @Test
    void inAndNotInMatchThroughExistsOverTheList() {
        Map<String, Object> inParams = new HashMap<>();
        Map<String, Object> notInParams = new HashMap<>();

        String in = where(new FilterClause("category", "in", array("Food", "Fuel")), new HashSet<>(), inParams);
        String notIn = where(new FilterClause("category", "not_in", array("Food", "Fuel")), new HashSet<>(), notInParams);

        assertEquals(" WHERE t.user_id = :userId AND EXISTS (" + SEMI + "cx.name IN (:f0))", in);
        assertEquals(" WHERE t.user_id = :userId AND NOT EXISTS (" + SEMI + "cx.name IN (:f0))", notIn);
        assertEquals(List.of("Food", "Fuel"), inParams.get("f0"));
        assertEquals(List.of("Food", "Fuel"), notInParams.get("f0"));
    }

    @Test
    void whenTheQueryGroupsByCategoryTheJoinedCategoryMustMatchToo() {
        Set<String> joins = new HashSet<>();
        queryBuilder.expression("category", joins);
        Map<String, Object> params = new HashMap<>();

        String in = where(new FilterClause("category", "in", array("Food", "Fuel")), joins, params);

        assertEquals(" WHERE t.user_id = :userId AND (EXISTS (" + SEMI + "cx.name IN (:f0)) AND c.name IN (:f0r))", in);
        assertEquals(List.of("Food", "Fuel"), params.get("f0"));
        assertEquals(List.of("Food", "Fuel"), params.get("f0r"));
    }

    @Test
    void groupedNegationKeepsUncategorisedRowsLikeTheEnumNegation() {
        Set<String> joins = new HashSet<>();
        queryBuilder.expression("category", joins);
        Map<String, Object> params = new HashMap<>();

        String isNot = where(new FilterClause("category", "is_not", JSON.textNode("Food")), joins, params);

        assertEquals(" WHERE t.user_id = :userId AND (NOT EXISTS (" + SEMI + "cx.name = :f0)"
                + " AND (c.name <> :f0r OR c.name IS NULL))", isNot);
    }

    @Test
    void eachCategoryFilterBindsItsOwnParameters() {
        Map<String, Object> params = new HashMap<>();

        String where = queryBuilder.buildWhere(List.of(new FilterClause("category", "is", JSON.textNode("Food")),
                new FilterClause("category", "is_not", JSON.textNode("Fuel"))), userId, params, new HashSet<>());

        assertEquals(" WHERE t.user_id = :userId AND EXISTS (" + SEMI + "cx.name = :f0) AND NOT EXISTS ("
                + SEMI + "cx.name = :f1)", where);
        assertEquals("Food", params.get("f0"));
        assertEquals("Fuel", params.get("f1"));
    }
}
