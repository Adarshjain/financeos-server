package com.financeos.domain.category;

import com.financeos.core.exception.ValidationException;
import com.financeos.domain.transaction.TransactionType;
import com.financeos.support.TestCategories;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CategoryCatalogTest {

    private final CategoryCatalog catalog = TestCategories.catalog();

    @Test
    void holdsTheWholeFixedList() {
        assertEquals(39, catalog.all().size());
        assertEquals(142, catalog.all().stream().mapToInt(c -> c.subs().size()).sum());
    }

    @Test
    void looksUpByKey() {
        CategoryCatalog.Cat food = catalog.category("food").orElseThrow();
        assertEquals("Food", food.name());
        assertEquals("cat-fork-knife", food.icon());
        assertEquals(CategoryDirection.OUT, food.direction());
        assertFalse(food.excludedByDefault());
        assertEquals("food.dining", food.subs().get(0).key());
        assertEquals("food", catalog.subcategory("food.dining").orElseThrow().categoryKey());
        assertTrue(catalog.category("nope").isEmpty());
        assertTrue(catalog.category(null).isEmpty());
        assertTrue(catalog.subcategory("food.nope").isEmpty());
    }

    @Test
    void excludedByDefaultIsSelfTransferCardBillAndTopUp() {
        assertEquals(List.of("card_bill", "self_transfer", "top_up"), catalog.all().stream()
                .filter(CategoryCatalog.Cat::excludedByDefault).map(CategoryCatalog.Cat::key).sorted().toList());
    }

    @Test
    void listsPerDirectionInThatDirectionsOrder() {
        List<CategoryCatalog.Cat> out = catalog.list(CategoryDirection.OUT);
        assertEquals("food", out.get(0).key());
        assertEquals("groceries", out.get(1).key());
        assertTrue(out.stream().anyMatch(c -> c.key().equals("gifts")), "BOTH categories show in the OUT list");
        assertTrue(out.stream().noneMatch(c -> c.key().equals("salary")));
        assertEquals(out.stream().map(CategoryCatalog.Cat::outOrder).sorted().toList(),
                out.stream().map(CategoryCatalog.Cat::outOrder).toList());

        List<CategoryCatalog.Cat> in = catalog.list(CategoryDirection.IN);
        assertEquals("salary", in.get(0).key());
        assertEquals("earnings", in.get(1).key());
        assertTrue(in.stream().anyMatch(c -> c.key().equals("other")));
        assertTrue(in.stream().noneMatch(c -> c.key().equals("food")));
        assertEquals(in.stream().map(CategoryCatalog.Cat::inOrder).sorted().toList(),
                in.stream().map(CategoryCatalog.Cat::inOrder).toList());
    }

    @Test
    void acceptsCategoriesThatFitTheTransactionDirection() {
        assertDoesNotThrow(() -> catalog.validate(TransactionType.DEBIT, "food", "food.dining"));
        assertDoesNotThrow(() -> catalog.validate(TransactionType.CREDIT, "salary", null));
        assertDoesNotThrow(() -> catalog.validate(TransactionType.DEBIT, "gifts", null));
        assertDoesNotThrow(() -> catalog.validate(TransactionType.CREDIT, "gifts", null));
        assertDoesNotThrow(() -> catalog.validate(TransactionType.CREDIT, "other", "other.verification"));
        assertDoesNotThrow(() -> catalog.validate(TransactionType.DEBIT, null, null), "no category is a valid clear");
    }

    @Test
    void rejectsADirectionThatDoesNotFit() {
        assertThrows(ValidationException.class, () -> catalog.validate(TransactionType.CREDIT, "food", null));
        assertThrows(ValidationException.class, () -> catalog.validate(TransactionType.DEBIT, "salary", null));
    }

    @Test
    void rejectsASubcategoryWhoseOwnDirectionDoesNotFit() {
        // card_bill is BOTH, but card_bill.credit_card is money out only.
        assertEquals(CategoryDirection.OUT, catalog.subcategory("card_bill.credit_card").orElseThrow().direction());
        assertDoesNotThrow(() -> catalog.validate(TransactionType.DEBIT, "card_bill", "card_bill.credit_card"));
        assertThrows(ValidationException.class,
                () -> catalog.validate(TransactionType.CREDIT, "card_bill", "card_bill.credit_card"));
    }

    @Test
    void rejectsUnknownKeysAndASubcategoryOfAnotherCategory() {
        assertThrows(ValidationException.class, () -> catalog.validate(TransactionType.DEBIT, "nope", null));
        assertThrows(ValidationException.class, () -> catalog.validate(TransactionType.DEBIT, "food", "food.nope"));
        assertThrows(ValidationException.class, () -> catalog.validate(TransactionType.DEBIT, "food", "travel.flights"));
        assertThrows(ValidationException.class, () -> catalog.validate(TransactionType.DEBIT, null, "food.dining"));
    }

    @Test
    void validateKeysChecksExistenceAndMembershipOnly() {
        assertDoesNotThrow(() -> catalog.validateKeys("salary", "salary.bonus"));
        assertDoesNotThrow(() -> catalog.validateKeys("food", null));
        assertThrows(ValidationException.class, () -> catalog.validateKeys(null, null));
        assertThrows(ValidationException.class, () -> catalog.validateKeys("nope", null));
        assertThrows(ValidationException.class, () -> catalog.validateKeys("food", "travel.flights"));
    }
}
