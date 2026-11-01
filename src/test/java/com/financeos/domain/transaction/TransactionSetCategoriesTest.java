package com.financeos.domain.transaction;

import com.financeos.domain.category.Tag;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Transaction.setCategories diffs by category id. Callers pass categories loaded in a different
 * persistence context than the transaction's own (lazy proxy) categories, so these tests always use
 * a separate Tag instance with the same id. Instance equality would never match those, so a
 * kept category would be deleted and re-inserted.
 */
class TransactionSetCategoriesTest {

    private static Tag category(UUID id, String name) {
        Tag c = new Tag();
        c.setId(id);
        c.setName(name);
        return c;
    }

    /** A different instance with the same id — what another persistence context hands back. */
    private static Tag copyOf(Tag c) {
        return category(c.getId(), c.getName());
    }

    private static Transaction transactionWith(Tag... categories) {
        Transaction txn = new Transaction();
        txn.setTags(new HashSet<>(Set.of(categories)));
        return txn;
    }

    private static Set<UUID> categoryIds(Transaction txn) {
        return txn.getTags().stream()
                .map(tc -> tc.getTag().getId())
                .collect(Collectors.toSet());
    }

    private static TransactionTag rowFor(Transaction txn, UUID categoryId) {
        return txn.getTags().stream()
                .filter(tc -> tc.getTag().getId().equals(categoryId))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void removingOneCategory_keepsTheExistingRowForTheOtherByIdentity() {
        Tag food = category(UUID.randomUUID(), "Food");
        Tag travel = category(UUID.randomUUID(), "Travel");
        Transaction txn = transactionWith(food, travel);
        TransactionTag keptRow = rowFor(txn, food.getId());

        txn.setTags(Set.of(copyOf(food)));

        assertEquals(Set.of(food.getId()), categoryIds(txn));
        assertSame(keptRow, rowFor(txn, food.getId()), "kept category must not be deleted and re-inserted");
    }

    @Test
    void addingACategory_keepsTheExistingRowAndAddsOneRow() {
        Tag food = category(UUID.randomUUID(), "Food");
        Tag travel = category(UUID.randomUUID(), "Travel");
        Transaction txn = transactionWith(food);
        TransactionTag keptRow = rowFor(txn, food.getId());

        txn.setTags(Set.of(copyOf(food), travel));

        assertEquals(Set.of(food.getId(), travel.getId()), categoryIds(txn));
        assertEquals(2, txn.getTags().size());
        assertSame(keptRow, rowFor(txn, food.getId()));
    }

    @Test
    void sameCategories_asOtherInstances_changeNothing() {
        Tag food = category(UUID.randomUUID(), "Food");
        Tag travel = category(UUID.randomUUID(), "Travel");
        Transaction txn = transactionWith(food, travel);
        Set<TransactionTag> before = Set.copyOf(txn.getTags());

        txn.setTags(Set.of(copyOf(food), copyOf(travel)));

        assertEquals(2, txn.getTags().size());
        for (TransactionTag row : before) {
            assertSame(row, rowFor(txn, row.getTag().getId()));
        }
    }

    @Test
    void replacingAllCategories_dropsOldRowsAndAddsNewOnes() {
        Tag food = category(UUID.randomUUID(), "Food");
        Tag travel = category(UUID.randomUUID(), "Travel");
        Transaction txn = transactionWith(food);

        txn.setTags(Set.of(travel));

        assertEquals(Set.of(travel.getId()), categoryIds(txn));
        assertEquals(1, txn.getTags().size());
    }

    @Test
    void twoInstancesOfOneNewCategory_addASingleRow() {
        Tag travel = category(UUID.randomUUID(), "Travel");
        Transaction txn = new Transaction();

        txn.setTags(new HashSet<>(Set.of(travel, copyOf(travel))));

        assertEquals(1, txn.getTags().size());
        assertEquals(Set.of(travel.getId()), categoryIds(txn));
    }

    @Test
    void emptySet_clearsAllCategories() {
        Transaction txn = transactionWith(category(UUID.randomUUID(), "Food"));

        txn.setTags(Set.of());

        assertTrue(txn.getTags().isEmpty());
    }

    @Test
    void nullSet_clearsAllCategories() {
        Transaction txn = transactionWith(category(UUID.randomUUID(), "Food"));

        txn.setTags(null);

        assertTrue(txn.getTags().isEmpty());
    }
}
