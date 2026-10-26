package com.financeos.domain.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class AccountFilterIdRewriteTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String PHONEPE = "0b3c7a52-6f43-4d61-9a0f-1c2d3e4f5a6b";
    private static final String HDFC_1 = "7e1f2a3b-4c5d-4e6f-8a9b-0c1d2e3f4a5b";
    private static final String HDFC_2 = "11111111-2222-4333-8444-555555555555";
    private static final Map<String, List<String>> ACCOUNTS = Map.of(
            "PhonePe", List.of(PHONEPE),
            "HDFC", List.of(HDFC_1, HDFC_2));

    private static String def(String filters) {
        return "{\"type\":\"KPI\",\"measure\":\"amount\",\"filters\":" + filters + "}";
    }

    private static JsonNode filter(String json, int i) throws Exception {
        return MAPPER.readTree(json).get("filters").get(i);
    }

    private static String account(String op, String value) {
        return "{\"field\":\"account\",\"operator\":\"" + op + "\",\"value\":" + value + "}";
    }

    @Test
    void aSingleNameBecomesItsId() throws Exception {
        String out = AccountFilterIdRewrite.rewrite(def("[" + account("is", "\"PhonePe\"") + "]"), ACCOUNTS);
        assertEquals(PHONEPE, filter(out, 0).get("value").asText());
        assertEquals("is", filter(out, 0).get("operator").asText());
        assertEquals("KPI", MAPPER.readTree(out).get("type").asText());
    }

    @Test
    void anAmbiguousNameStaysUnderEveryOperator() {
        AtomicInteger left = new AtomicInteger();
        for (String op : List.of("is", "is_not")) {
            assertNull(AccountFilterIdRewrite.rewrite(def("[" + account(op, "\"HDFC\"") + "]"), ACCOUNTS, left));
        }
        for (String op : List.of("in", "not_in")) {
            assertNull(AccountFilterIdRewrite.rewrite(def("[" + account(op, "[\"HDFC\"]") + "]"), ACCOUNTS, left));
            assertNull(AccountFilterIdRewrite.rewrite(def("[" + account(op, "\"HDFC\"") + "]"), ACCOUNTS, left));
        }
        assertEquals(6, left.get());
    }

    @Test
    void aScalarInWithOneAccountBecomesItsId() throws Exception {
        String out = AccountFilterIdRewrite.rewrite(def("[" + account("in", "\"PhonePe\"") + "]"), ACCOUNTS);
        assertEquals(PHONEPE, filter(out, 0).get("value").asText());
    }

    @Test
    void numbersKeepTheirWrittenPrecision() throws Exception {
        String out = AccountFilterIdRewrite.rewrite("{\"filters\":[" + account("is", "\"PhonePe\"") + ","
                + "{\"field\":\"amount\",\"operator\":\"greater_than\",\"value\":1234.50}]}", ACCOUNTS);
        assertEquals(true, out.contains("1234.50"));
    }

    @Test
    void anUnknownNameStays() {
        AtomicInteger left = new AtomicInteger();
        assertNull(AccountFilterIdRewrite.rewrite(def("[" + account("is", "\"Gone\"") + "]"), ACCOUNTS, left));
        assertEquals(1, left.get());
    }

    @Test
    void namesAreMatchedCaseSensitively() {
        assertNull(AccountFilterIdRewrite.rewrite(def("[" + account("is", "\"phonepe\"") + "]"), ACCOUNTS));
    }

    @Test
    void anIdIsUntouched() {
        assertNull(AccountFilterIdRewrite.rewrite(def("[" + account("is", "\"" + PHONEPE + "\"") + "]"), ACCOUNTS));
    }

    @Test
    void aMixedArrayRewritesNamesKeepsIdsAndUnknowns() throws Exception {
        String out = AccountFilterIdRewrite.rewrite(
                def("[" + account("in", "[\"PhonePe\",\"" + HDFC_1 + "\",\"Gone\"]") + "]"), ACCOUNTS);
        assertEquals(List.of(PHONEPE, HDFC_1, "Gone"), MAPPER.convertValue(filter(out, 0).get("value"), List.class));
    }

    @Test
    void otherOperatorsAndFieldsAreUntouched() {
        assertNull(AccountFilterIdRewrite.rewrite(def("[" + account("contains", "\"PhonePe\"") + ","
                + "{\"field\":\"category\",\"operator\":\"is\",\"value\":\"PhonePe\"}]"), ACCOUNTS));
    }

    @Test
    void otherFiltersAndKeysSurviveARewrite() throws Exception {
        String out = AccountFilterIdRewrite.rewrite(def("[{\"field\":\"category\",\"operator\":\"is\",\"value\":\"Food\"},"
                + account("is", "\"PhonePe\"") + "]"), ACCOUNTS);
        assertEquals("Food", filter(out, 0).get("value").asText());
        assertEquals(PHONEPE, filter(out, 1).get("value").asText());
        assertEquals("amount", MAPPER.readTree(out).get("measure").asText());
    }

    @Test
    void aDefinitionWithoutFiltersIsUnchanged() {
        assertNull(AccountFilterIdRewrite.rewrite("{\"type\":\"KPI\"}", ACCOUNTS));
        assertNull(AccountFilterIdRewrite.rewrite("{\"filters\":null}", ACCOUNTS));
    }

    @Test
    void aSecondPassChangesNothing() {
        String once = AccountFilterIdRewrite.rewrite(def("[" + account("is", "\"PhonePe\"") + "]"), ACCOUNTS);
        assertNull(AccountFilterIdRewrite.rewrite(once, ACCOUNTS));
    }
}
