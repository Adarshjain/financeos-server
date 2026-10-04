package com.financeos.domain.transaction;

import com.financeos.domain.category.Category;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Transaction.setCategories diffs by category id. Callers pass categories loaded in a different
 * persistence context than the transaction's own (lazy proxy) categories, so these tests always use
 * a separate Category instance with the same id. Instance equality would never match those, so a
 * kept category would be deleted and re-inserted.
 */
class TransactionSetCategoriesTest {

    private static Category category(UUID id, String name) {
        Category c = new Category();
        c.setId(id);
        c.setName(name);
        return c;
    }

    /** A different instance with the same id — what another persistence context hands back. */
    private static Category copyOf(Category c) {
        return category(c.getId(), c.getName());
    }

    private static Transaction transactionWith(Category... categories) {
        Transaction txn = new Transaction();
        txn.setCategories(new HashSet<>(Set.of(categories)));
        return txn;
    }

    private static Set<UUID> categoryIds(Transaction txn) {
        return txn.getCategories().stream()
                .map(tc -> tc.getCategory().getId())
                .collect(Collectors.toSet());
    }

    private static TransactionCategory rowFor(Transaction txn, UUID categoryId) {
        return txn.getCategories().stream()
                .filter(tc -> tc.getCategory().getId().equals(categoryId))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void removingOneCategory_keepsTheExistingRowForTheOtherByIdentity() {
        Category food = category(UUID.randomUUID(), "Food");
        Category travel = category(UUID.randomUUID(), "Travel");
        Transaction txn = transactionWith(food, travel);
        TransactionCategory keptRow = rowFor(txn, food.getId());

        txn.setCategories(Set.of(copyOf(food)));

        assertEquals(Set.of(food.getId()), categoryIds(txn));
        assertSame(keptRow, rowFor(txn, food.getId()), "kept category must not be deleted and re-inserted");
    }

    @Test
    void addingACategory_keepsTheExistingRowAndAddsOneRow() {
        Category food = category(UUID.randomUUID(), "Food");
        Category travel = category(UUID.randomUUID(), "Travel");
        Transaction txn = transactionWith(food);
        TransactionCategory keptRow = rowFor(txn, food.getId());

        txn.setCategories(Set.of(copyOf(food), travel));

        assertEquals(Set.of(food.getId(), travel.getId()), categoryIds(txn));
        assertEquals(2, txn.getCategories().size());
        assertSame(keptRow, rowFor(txn, food.getId()));
    }

    @Test
    void sameCategories_asOtherInstances_changeNothing() {
        Category food = category(UUID.randomUUID(), "Food");
        Category travel = category(UUID.randomUUID(), "Travel");
        Transaction txn = transactionWith(food, travel);
        Set<TransactionCategory> before = Set.copyOf(txn.getCategories());

        txn.setCategories(Set.of(copyOf(food), copyOf(travel)));

        assertEquals(2, txn.getCategories().size());
        for (TransactionCategory row : before) {
            assertSame(row, rowFor(txn, row.getCategory().getId()));
        }
    }

    @Test
    void replacingAllCategories_dropsOldRowsAndAddsNewOnes() {
        Category food = category(UUID.randomUUID(), "Food");
        Category travel = category(UUID.randomUUID(), "Travel");
        Transaction txn = transactionWith(food);

        txn.setCategories(Set.of(travel));

        assertEquals(Set.of(travel.getId()), categoryIds(txn));
        assertEquals(1, txn.getCategories().size());
    }

    @Test
    void twoInstancesOfOneNewCategory_addASingleRow() {
        Category travel = category(UUID.randomUUID(), "Travel");
        Transaction txn = new Transaction();

        txn.setCategories(new HashSet<>(Set.of(travel, copyOf(travel))));

        assertEquals(1, txn.getCategories().size());
        assertEquals(Set.of(travel.getId()), categoryIds(txn));
    }

    @Test
    void emptySet_clearsAllCategories() {
        Transaction txn = transactionWith(category(UUID.randomUUID(), "Food"));

        txn.setCategories(Set.of());

        assertTrue(txn.getCategories().isEmpty());
    }

    @Test
    void nullSet_clearsAllCategories() {
        Transaction txn = transactionWith(category(UUID.randomUUID(), "Food"));

        txn.setCategories(null);

        assertTrue(txn.getCategories().isEmpty());
    }
}
