package com.financeos.domain.categorization.builtin;

import com.financeos.domain.category.CategoryCatalog;
import com.financeos.domain.transaction.TransactionType;
import com.financeos.gmail.extract.Direction;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuiltinDataIntegrityTest {

    private static BuiltinData data;

    @BeforeAll
    static void load() {
        data = BuiltinData.load();
    }

    private static final CategoryCatalog CATALOG = CategoryCatalog.shared();

    @Test
    void everyProducedKeyIsInTheTaxonomy() {
        Set<String> produced = data.producedCategories();
        assertFalse(produced.isEmpty());
        for (String key : produced) {
            assertTrue(CATALOG.subcategory(key).isPresent() || CATALOG.category(key).isPresent(), "not in the taxonomy: " + key);
        }
        // the engine splits transit into metro, local bus and the general category
        assertTrue(produced.containsAll(Set.of("transport", "transport.metro", "transport.local_bus", "food.delivery")));
    }

    @Test
    void everyTargetHasAValidDirectionAndCreditReading() {
        List<BuiltinData.Target> targets = new ArrayList<>();
        data.keywords.forEach(r -> targets.add(r.category()));
        data.merchants.forEach(m -> targets.add(m.category()));
        data.mccExact.values().forEach(targets::add);
        data.mccRanges.forEach(r -> targets.add(r.category()));
        for (BuiltinData.Target t : targets) {
            assertTrue(t.direction() != null, "category and sub-category directions clash: " + t.key());
            assertEquals(t.subcategoryKey() == null ? t.key() : t.categoryKey(), t.categoryKey());
            BuiltinData.Target out = data.resolve(t, Direction.DEBIT);
            if (out != null) {
                assertTrue(CATALOG.fits(TransactionType.DEBIT, out.categoryKey(), out.subcategoryKey()), "debit: " + t.key());
            }
            BuiltinData.Target in = data.resolve(t, Direction.CREDIT);
            if (in != null) {
                assertTrue(CATALOG.fits(TransactionType.CREDIT, in.categoryKey(), in.subcategoryKey()), "credit: " + t.key());
            }
        }
        data.creditTargets().forEach(t -> assertTrue(CATALOG.fits(TransactionType.CREDIT, t.categoryKey(), t.subcategoryKey()), t.key()));
    }

    @Test
    void dictionaryIsBroadAndHasNoDuplicateNames() {
        Set<String> names = new HashSet<>();
        for (BuiltinData.Merchant m : data.merchants) {
            assertTrue(names.add(m.name()), "duplicate merchant name: " + m.name());
            assertFalse(m.name().isBlank());
        }
        assertTrue(data.merchants.size() >= 500, "expected several hundred merchants, got " + data.merchants.size());
    }

    @Test
    void ruleIdsAreUniqueAcrossLinkAndKeywordFiles() {
        Set<String> ids = new HashSet<>();
        data.link.forEach(r -> assertTrue(ids.add(r.id()), "duplicate rule id: " + r.id()));
        data.keywords.forEach(r -> assertTrue(ids.add(r.id()), "duplicate rule id: " + r.id()));
        data.link.forEach(r -> assertEquals(null, r.category()));
        data.keywords.forEach(r -> assertTrue(r.category() != null));
    }

    @Test
    void mccTablesLoad() {
        assertFalse(data.mccExact.isEmpty());
        assertFalse(data.mccRanges.isEmpty());
        data.mccExact.keySet().forEach(code -> assertTrue(code.matches("\\d{4}"), code));
    }

    @Test
    void peopleWordListsLoad() {
        assertTrue(data.honorifics.contains("mr"));
        assertTrue(data.businessWords.contains("traders"));
    }
}
