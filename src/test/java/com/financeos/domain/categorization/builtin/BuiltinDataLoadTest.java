package com.financeos.domain.categorization.builtin;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Loading fails fast on bad data: every source here is a small made-up YAML tree. */
class BuiltinDataLoadTest {

    private static Map<String, Object> files() {
        Map<String, Object> f = new HashMap<>();
        f.put("categories.yml", Map.of("groups", Map.of("Food", List.of("Dining", "Groceries"))));
        f.put("link.yml", Map.of("rules", List.of()));
        f.put("keywords.yml", Map.of("rules", List.of()));
        f.put("merchants.yml", Map.of("merchants", Map.of("Dining", List.of("zyxq cafe"), "Groceries", List.of("zyxq mart"))));
        f.put("mcc.yml", Map.of("codes", Map.of()));
        f.put("people.yml", Map.of("honorifics", List.of("mr"), "businessWords", List.of("store")));
        return f;
    }

    private static BuiltinData load(Map<String, Object> f) {
        return BuiltinData.load(f::get);
    }

    @Test
    void aMinimalValidTreeLoads() {
        assertEquals(2, load(files()).merchants.size());
    }

    @Test
    void unknownCategoryFails() {
        Map<String, Object> f = files();
        f.put("merchants.yml", Map.of("merchants", Map.of("Nonsense", List.of("zyxq"))));
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> load(f));
        assertTrue(e.getMessage().contains("merchants.yml"));
    }

    @Test
    void duplicateMerchantFails() {
        Map<String, Object> f = files();
        f.put("merchants.yml", Map.of("merchants", Map.of("Dining", List.of("zyxq cafe"), "Groceries", List.of("Zyxq Cafe"))));
        assertThrows(IllegalStateException.class, () -> load(f));
    }

    @Test
    void badRegexFails() {
        Map<String, Object> f = files();
        f.put("keywords.yml", Map.of("rules", List.of(Map.of("id", "bad", "category", "Dining", "counterparty", List.of("(unclosed")))));
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> load(f));
        assertTrue(e.getMessage().contains("bad"));
    }

    @Test
    void ruleWithUnknownCategoryOrNoConditionFails() {
        Map<String, Object> f = files();
        f.put("keywords.yml", Map.of("rules", List.of(Map.of("id", "x", "category", "Nope", "counterparty", List.of("a")))));
        assertThrows(IllegalStateException.class, () -> load(f));
        Map<String, Object> g = files();
        g.put("keywords.yml", Map.of("rules", List.of(Map.of("id", "x", "category", "Dining"))));
        assertThrows(IllegalStateException.class, () -> load(g));
    }
}
