package com.financeos.domain.report.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.financeos.domain.report.datasource.impl.TransactionsDatasource;
import com.financeos.domain.report.definition.FilterClause;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The transactions {@code account} filter: values that are account ids match the transaction's
 * account id (no accounts join), names match the account's name as before, and a list may mix
 * both (positive operators match either, negated ones exclude both).
 */
class TransactionQueryBuilderAccountIdFilterTest {

    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
    private static final String A = "0b3c7a52-6f43-4d61-9a0f-1c2d3e4f5a6b";
    private static final String B = "7e1f2a3b-4c5d-4e6f-8a9b-0c1d2e3f4a5b";

    private final UUID userId = UUID.randomUUID();
    private TransactionQueryBuilder queryBuilder;
    private Set<String> joins;
    private Map<String, Object> params;

    @BeforeEach
    void setUp() {
        DateRangeResolver dates = new DateRangeResolver(4);
        queryBuilder = (TransactionQueryBuilder) new TransactionsDatasource(new SqlPredicates(dates), dates).queryBuilder();
        joins = new HashSet<>();
        params = new HashMap<>();
    }

    private String where(String op, JsonNode value) {
        return queryBuilder.buildWhere(List.of(new FilterClause("account", op, value)), userId, params, joins);
    }

    private static JsonNode array(String... values) {
        var array = JSON.arrayNode();
        for (String v : values) {
            array.add(v);
        }
        return array;
    }

    @Test
    void anIdMatchesTheAccountIdWithoutTheAccountsJoin() {
        assertEquals(" WHERE t.user_id = :userId AND t.account_id IN (:f0i)", where("is", JSON.textNode(A)));
        assertEquals(List.of(A), params.get("f0i"));
        assertFalse(joins.contains(TransactionQueryBuilder.JOIN_ACCOUNTS));
    }

    @Test
    void aListOfIdsMatchesAnyOfThem() {
        assertEquals(" WHERE t.user_id = :userId AND t.account_id IN (:f0i)", where("in", array(A, B)));
        assertEquals(List.of(A, B), params.get("f0i"));
    }

    @Test
    void anUppercaseIdIsMatchedLowercase() {
        where("is", JSON.textNode(A.toUpperCase()));
        assertEquals(List.of(A), params.get("f0i"));
    }

    @Test
    void negatedIdsExcludeThoseAccounts() {
        assertEquals(" WHERE t.user_id = :userId AND t.account_id NOT IN (:f0i)", where("not_in", array(A, B)));
        params.clear();
        assertEquals(" WHERE t.user_id = :userId AND t.account_id NOT IN (:f0i)", where("is_not", JSON.textNode(A)));
    }

    @Test
    void aMixedListMatchesByIdOrByName() {
        String sql = where("in", array(A, "Main Bank"));
        assertEquals(" WHERE t.user_id = :userId AND (t.account_id IN (:f0i) OR a.name IN (:f0n))", sql);
        assertEquals(List.of(A), params.get("f0i"));
        assertEquals(List.of("Main Bank"), params.get("f0n"));
        assertTrue(joins.contains(TransactionQueryBuilder.JOIN_ACCOUNTS));
    }

    @Test
    void aNegatedMixedListExcludesBoth() {
        String sql = where("not_in", array(A, "Main Bank"));
        assertEquals(" WHERE t.user_id = :userId AND (t.account_id NOT IN (:f0i) AND (a.name NOT IN (:f0n) OR a.name IS NULL))",
                sql);
    }

    @Test
    void namesOnlyKeepTheStandardNamePredicate() {
        assertEquals(" WHERE t.user_id = :userId AND a.name = :f0", where("is", JSON.textNode("Main Bank")));
        assertEquals("Main Bank", params.get("f0"));
        params.clear();
        assertEquals(" WHERE t.user_id = :userId AND (a.name NOT IN (:f0) OR a.name IS NULL)",
                where("not_in", array("Main Bank", "Wallet")));
    }

    @Test
    void aUuidLookingNameOfTheWrongLengthIsAName() {
        assertEquals(" WHERE t.user_id = :userId AND a.name = :f0", where("is", JSON.textNode("1-2-3-4-5")));
    }
}
