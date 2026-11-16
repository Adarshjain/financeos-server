package com.financeos.domain.report.engine;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.financeos.domain.report.datasource.FieldType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

/** Malformed user filter values must surface as IllegalArgumentException (mapped to 400), never a 500. */
class SqlPredicatesInvalidValueTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private SqlPredicates predicates;
    private Map<String, Object> params;

    @BeforeEach
    void setUp() {
        predicates = new SqlPredicates(new DateRangeResolver(4));
        params = new HashMap<>();
    }

    private static JsonNode json(String s) throws Exception {
        return JSON.readTree(s);
    }

    private IllegalArgumentException rejected(FieldType type, String op, JsonNode value) {
        return assertThrows(IllegalArgumentException.class,
                () -> predicates.build(type, "t.col", op, value, params, "f0"));
    }

    @Test
    void shortYearDateIsRejectedForEveryScalarOperator() {
        for (String op : new String[] {"is", "after", "before"}) {
            IllegalArgumentException e = rejected(FieldType.DATE, op, TextNode.valueOf("261-11-01"));
            assertTrue(e.getMessage().contains("261-11-01"), e.getMessage());
        }
    }

    @Test
    void garbageDateIsRejected() {
        assertTrue(rejected(FieldType.DATE, "is", TextNode.valueOf("not-a-date")).getMessage().contains("yyyy-MM-dd"));
        rejected(FieldType.DATE, "is", TextNode.valueOf(""));
    }

    @Test
    void impossibleCalendarDateIsRejected() {
        rejected(FieldType.DATE, "is", TextNode.valueOf("2026-02-30"));
    }

    @Test
    void betweenRejectsMalformedMissingAndNonScalarBounds() throws Exception {
        rejected(FieldType.DATE, "between", json("{\"from\":\"261-11-01\",\"to\":\"2026-11-30\"}"));
        rejected(FieldType.DATE, "between", json("{\"from\":\"2026-11-01\",\"to\":\"bad\"}"));
        rejected(FieldType.DATE, "between", json("{\"from\":\"2026-11-01\"}"));
        rejected(FieldType.DATE, "between", json("{\"from\":null,\"to\":\"2026-11-30\"}"));
        rejected(FieldType.DATE, "between", json("{\"from\":{},\"to\":\"2026-11-30\"}"));
        rejected(FieldType.DATE, "between", TextNode.valueOf("2026-11-01"));
    }

    @Test
    void validDatesStillBind() throws Exception {
        predicates.build(FieldType.DATE, "t.d", "is", TextNode.valueOf("2026-11-01"), params, "f0");
        assertEquals(LocalDate.of(2026, 11, 1), params.get("f0"));
        predicates.build(FieldType.DATE, "t.d", "between", json("{\"from\":\"2026-11-01\",\"to\":\"2026-11-30\"}"), params, "g");
        assertEquals(LocalDate.of(2026, 11, 30), params.get("gb"));
    }

    @Test
    void relativeOperandsAreRejected() throws Exception {
        rejected(FieldType.DATE, "last_x_days", json("{\"amount\":\"abc\"}"));
        rejected(FieldType.DATE, "last_x_days", json("{\"amount\":-3}"));
        rejected(FieldType.DATE, "next_x_days", json("{}"));
        rejected(FieldType.DATE, "last_x_months", TextNode.valueOf("3"));
        rejected(FieldType.DATE, "no_such_operator", TextNode.valueOf("x"));
    }

    @Test
    void relativeOperandOutOfCalendarRangeIsRejected() throws Exception {
        rejected(FieldType.DATE, "last_x_years", json("{\"amount\":2000000000}"));
    }

    @Test
    void validRelativeOperandStillBinds() throws Exception {
        String sql = predicates.build(FieldType.DATE, "t.d", "last_x_days", json("{\"amount\":7}"), params, "f0");
        assertEquals("t.d BETWEEN :f0a AND :f0b", sql);
    }

    @Test
    void nonNumericNumberIsRejected() throws Exception {
        for (String op : new String[] {"equals", "greater_than", "less_than"}) {
            assertTrue(rejected(FieldType.NUMBER, op, TextNode.valueOf("12abc")).getMessage().contains("12abc"));
        }
        rejected(FieldType.NUMBER, "equals", json("{}"));
        rejected(FieldType.NUMBER, "equals", json("null"));
        rejected(FieldType.NUMBER, "equals", TextNode.valueOf(""));
    }

    @Test
    void numberBetweenRejectsBadMissingAndNonNumericBounds() throws Exception {
        rejected(FieldType.NUMBER, "between", json("{\"from\":\"x\",\"to\":5}"));
        rejected(FieldType.NUMBER, "between", json("{\"from\":1}"));
        rejected(FieldType.NUMBER, "between", json("{\"from\":null,\"to\":5}"));
        rejected(FieldType.NUMBER, "between", TextNode.valueOf("1"));
    }

    @Test
    void validNumbersStillBind() throws Exception {
        predicates.build(FieldType.NUMBER, "t.n", "equals", json("12.5"), params, "f0");
        assertEquals(new BigDecimal("12.5"), params.get("f0"));
        predicates.build(FieldType.NUMBER, "t.n", "greater_than", TextNode.valueOf(" 40 "), params, "f1");
        assertEquals(new BigDecimal("40"), params.get("f1"));
        predicates.build(FieldType.NUMBER, "t.n", "between", json("{\"from\":1,\"to\":9}"), params, "f2");
        assertEquals(new BigDecimal("9"), params.get("f2b"));
    }

    @Test
    void effectiveRangeRejectsMalformedDates() throws Exception {
        DateRangeResolver resolver = new DateRangeResolver(4);
        assertThrows(IllegalArgumentException.class, () -> resolver.effectiveRange(
                new com.financeos.domain.report.definition.FilterClause("date", "is", TextNode.valueOf("261-11-01"))));
        assertThrows(IllegalArgumentException.class, () -> resolver.effectiveRange(
                new com.financeos.domain.report.definition.FilterClause("date", "between", json("{\"from\":\"2026-11-01\"}"))));
    }
}
