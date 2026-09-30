package com.financeos.domain.report.engine;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.TextNode;
import com.financeos.domain.report.datasource.FieldType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Enum negations must keep rows whose value is NULL (a missing value is never X). */
class SqlPredicatesNegationTest {

    private SqlPredicates predicates;
    private Map<String, Object> params;

    @BeforeEach
    void setUp() {
        predicates = new SqlPredicates(new DateRangeResolver(4));
        params = new HashMap<>();
    }

    @Test
    void enumIsNotAlsoMatchesNull() {
        String sql = predicates.build(FieldType.ENUM, "t.category", "is_not", TextNode.valueOf("Dining"), params, "f0");

        assertEquals("(t.category <> :f0 OR t.category IS NULL)", sql);
        assertEquals(Map.of("f0", "Dining"), params);
    }

    @Test
    void enumNotInAlsoMatchesNull() {
        var value = JsonNodeFactory.instance.arrayNode().add("Dining").add("Travel");

        String sql = predicates.build(FieldType.ENUM, "t.category", "not_in", value, params, "f1");

        assertEquals("(t.category NOT IN (:f1) OR t.category IS NULL)", sql);
        assertEquals(Map.of("f1", List.of("Dining", "Travel")), params);
    }

    @Test
    void enumPositiveOperatorsAreUnchanged() {
        assertEquals("t.category = :f0",
                predicates.build(FieldType.ENUM, "t.category", "is", TextNode.valueOf("Dining"), params, "f0"));
        assertEquals("t.category IN (:f1)",
                predicates.build(FieldType.ENUM, "t.category", "in", JsonNodeFactory.instance.arrayNode().add("A"), params, "f1"));
        assertEquals("Dining", params.get("f0"));
        assertEquals(List.of("A"), params.get("f1"));
    }

    @Test
    void unsupportedEnumOperatorStillRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> predicates.build(FieldType.ENUM, "x", "contains", TextNode.valueOf("a"), params, "f0"));
    }
}
