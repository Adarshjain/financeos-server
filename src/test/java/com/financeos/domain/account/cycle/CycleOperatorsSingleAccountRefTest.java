package com.financeos.domain.account.cycle;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.TextNode;
import com.financeos.domain.report.definition.FilterClause;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class CycleOperatorsSingleAccountRefTest {

    private static FilterClause f(String field, String op, JsonNode value) {
        return new FilterClause(field, op, value);
    }

    private static ArrayNode arr(String... values) {
        ArrayNode a = JsonNodeFactory.instance.arrayNode();
        for (String v : values) a.add(v);
        return a;
    }

    @Test
    void nullAccountFieldOrNullFiltersGiveNull() {
        assertNull(CycleOperators.singleAccountRef(null, List.of(f("account", "is", TextNode.valueOf("a")))));
        assertNull(CycleOperators.singleAccountRef("account", null));
    }

    @Test
    void noFilterOnTheAccountFieldGivesNull() {
        assertNull(CycleOperators.singleAccountRef("account", List.of(f("category", "is", TextNode.valueOf("food")))));
        assertNull(CycleOperators.singleAccountRef("account", List.of()));
    }

    @Test
    void isWithAScalarReturnsItsText() {
        assertEquals("acc-1", CycleOperators.singleAccountRef("account", List.of(f("account", "is", TextNode.valueOf("acc-1")))));
    }

    @Test
    void isWithANonTextScalarReturnsItsTextForm() {
        assertEquals("42", CycleOperators.singleAccountRef("account", List.of(f("account", "is", IntNode.valueOf(42)))));
    }

    @Test
    void inWithExactlyOneElementReturnsIt() {
        assertEquals("acc-1", CycleOperators.singleAccountRef("account", List.of(f("account", "in", arr("acc-1")))));
    }

    @Test
    void inWithTwoOrZeroElementsGivesNull() {
        assertNull(CycleOperators.singleAccountRef("account", List.of(f("account", "in", arr("a", "b")))));
        assertNull(CycleOperators.singleAccountRef("account", List.of(f("account", "in", arr()))));
    }

    @Test
    void inWithAScalarValueGivesNull() {
        assertNull(CycleOperators.singleAccountRef("account", List.of(f("account", "in", TextNode.valueOf("a")))));
    }

    @Test
    void isWithAnArrayValueGivesNull() {
        assertNull(CycleOperators.singleAccountRef("account", List.of(f("account", "is", arr("a")))));
    }

    @Test
    void aNullValueIsSkipped() {
        assertNull(CycleOperators.singleAccountRef("account", List.of(f("account", "is", null))));
    }

    @Test
    void otherOperatorsAreNotASingleAccount() {
        assertNull(CycleOperators.singleAccountRef("account", List.of(f("account", "is_not", TextNode.valueOf("a")))));
        assertNull(CycleOperators.singleAccountRef("account", List.of(f("account", "contains", TextNode.valueOf("a")))));
    }

    @Test
    void anUnusableAccountFilterIsSkippedForALaterSingleValueOne() {
        assertEquals("acc-2", CycleOperators.singleAccountRef("account", List.of(
                f("account", "in", arr("a", "b")), f("account", "is", TextNode.valueOf("acc-2")))));
    }

    @Test
    void filtersOnOtherFieldsAreIgnoredWhenPickingTheAccount() {
        assertEquals("acc-1", CycleOperators.singleAccountRef("account", List.of(
                f("category", "is", TextNode.valueOf("food")), f("account", "is", TextNode.valueOf("acc-1")))));
    }
}
