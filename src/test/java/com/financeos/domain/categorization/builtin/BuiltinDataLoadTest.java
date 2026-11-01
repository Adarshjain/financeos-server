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
        f.put("link.yml", Map.of("rules", List.of()));
        f.put("keywords.yml", Map.of("rules", List.of()));
        f.put("merchants.yml", Map.of("merchants", Map.of("food.dining", List.of("zyxq cafe"), "groceries", List.of("zyxq mart"))));
        f.put("mcc.yml", Map.of("codes", Map.of()));
        f.put("credit-side.yml", Map.of("credit", Map.of("bills.rent", "earnings.rent"), "none", List.of("emi")));
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
        f.put("merchants.yml", Map.of("merchants", Map.of("nonsense", List.of("zyxq"))));
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> load(f));
        assertTrue(e.getMessage().contains("merchants.yml"));
    }

    @Test
    void duplicateMerchantFails() {
        Map<String, Object> f = files();
        f.put("merchants.yml", Map.of("merchants", Map.of("food.dining", List.of("zyxq cafe"), "groceries", List.of("Zyxq Cafe"))));
        assertThrows(IllegalStateException.class, () -> load(f));
    }

    @Test
    void badRegexFails() {
        Map<String, Object> f = files();
        f.put("keywords.yml", Map.of("rules", List.of(Map.of("id", "bad", "category", "food.dining", "counterparty", List.of("(unclosed")))));
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> load(f));
        assertTrue(e.getMessage().contains("bad"));
    }

    @Test
    void ruleWithUnknownCategoryOrNoConditionFails() {
        Map<String, Object> f = files();
        f.put("keywords.yml", Map.of("rules", List.of(Map.of("id", "x", "category", "nope", "counterparty", List.of("a")))));
        assertThrows(IllegalStateException.class, () -> load(f));
        Map<String, Object> g = files();
        g.put("keywords.yml", Map.of("rules", List.of(Map.of("id", "x", "category", "food.dining"))));
        assertThrows(IllegalStateException.class, () -> load(g));
    }

    @Test
    void unknownSubcategoryAndBadCreditSideFail() {
        Map<String, Object> f = files();
        f.put("merchants.yml", Map.of("merchants", Map.of("food.nonsense", List.of("zyxq"))));
        assertThrows(IllegalStateException.class, () -> load(f));
        Map<String, Object> g = files();
        g.put("credit-side.yml", Map.of("credit", Map.of("bills.rent", "earnings.nope"), "none", List.of()));
        assertThrows(IllegalStateException.class, () -> load(g));
    }

    @Test
    void resolveReadsCreditsPerTheCreditSideFile() {
        BuiltinData d = load(files());
        BuiltinData.Target food = d.merchants.get(0).category();
        assertEquals("refund", d.resolve(food, com.financeos.gmail.extract.Direction.CREDIT).key());
        assertEquals(food, d.resolve(food, com.financeos.gmail.extract.Direction.DEBIT));
        assertEquals(food, d.resolve(food, null));
    }
}
