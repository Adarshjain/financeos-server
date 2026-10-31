package com.financeos.domain.categorization.builtin;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuiltinDataIntegrityTest {

    private static final List<String> EXPECTED_CATEGORIES = List.of(
            "Salary", "Bonus", "Interest", "Dividends", "Refunds", "Cashback", "Rent", "Business", "Gifts", "Other",
            "Groceries", "Dining", "Takeout", "Drinks",
            "Fuel", "Cabs", "Transit", "Tolls", "Vehicle",
            "Flights", "Trains", "Buses", "Hotels", "Travel", "Forex",
            "Shopping", "Clothing", "Electronics", "Furniture", "Beauty", "Books", "Jewellery", "Kids",
            "Maintenance", "Electricity", "Water", "Gas", "Mobile", "Internet", "TV", "Services", "Help", "Laundry",
            "Streaming", "Music", "Software", "Entertainment", "Gaming",
            "Medical", "Pharmacy", "Labs", "Fitness", "Grooming",
            "Education", "Courses",
            "EMI", "Investments", "Insurance", "Cash", "Charges", "Taxes",
            "People", "Donations", "Religious", "Pets", "Government", "Professional");

    private static BuiltinData data;

    @BeforeAll
    static void load() {
        data = BuiltinData.load();
    }

    @Test
    void categoriesAreTheAgreedList() {
        assertEquals(new HashSet<>(EXPECTED_CATEGORIES), data.groupOf.keySet());
        assertEquals(EXPECTED_CATEGORIES.size(), data.groupOf.size());
        assertEquals("Food", data.groupOf.get("Takeout"));
        assertEquals("Money", data.groupOf.get("EMI"));
    }

    @Test
    void everyProducedCategoryIsInTheList() {
        Set<String> produced = data.producedCategories();
        assertFalse(produced.isEmpty());
        for (String c : produced) {
            assertTrue(data.groupOf.containsKey(c), "not a known category: " + c);
        }
        assertTrue(data.groupOf.containsKey("People"), "People is produced by code, so it must be listed");
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
        data.keywords.forEach(r -> assertTrue(data.groupOf.containsKey(r.category())));
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
