package com.financeos.domain.transaction;

import com.financeos.core.exception.ValidationException;
import com.financeos.domain.categorization.CategoryRule;
import com.financeos.domain.categorization.CategoryRuleRepository;
import com.financeos.domain.user.User;
import com.financeos.support.TestCategories;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TransactionCategoryWriterTest {

    private CategoryRuleRepository ruleRepository;
    private TransactionCategoryWriter writer;
    private User user;

    @BeforeEach
    void setUp() {
        ruleRepository = mock(CategoryRuleRepository.class);
        writer = TestCategories.writer(ruleRepository);
        user = new User();
        user.setId(UUID.randomUUID());
    }

    private Transaction txn(TransactionType type) {
        Transaction t = new Transaction();
        t.setId(UUID.randomUUID());
        t.setUser(user);
        t.setType(type);
        return t;
    }

    private CategoryRule rule() {
        CategoryRule r = new CategoryRule();
        r.setId(UUID.randomUUID());
        r.setUser(user);
        when(ruleRepository.findByUserIdAndIdIn(user.getId(), List.of(r.getId()))).thenReturn(List.of(r));
        return r;
    }

    @Test
    void setsCategorySubcategorySourceAndTime() {
        Transaction t = txn(TransactionType.DEBIT);
        Instant before = Instant.now().minusSeconds(1);

        assertTrue(writer.set(t, "food", "food.dining", CategorySource.AUTO, (UUID) null));

        assertEquals("food", t.getCategoryKey());
        assertEquals("food.dining", t.getSubcategoryKey());
        assertEquals(CategorySource.AUTO, t.getCategorySource());
        assertTrue(t.getCategorySetAt().isAfter(before));
        assertNull(t.getAppliedRule());
    }

    @Test
    void aUserCategoryIsNeverOverwrittenByAnyOtherSource() {
        Transaction t = txn(TransactionType.DEBIT);
        writer.set(t, "food", null, CategorySource.USER, (UUID) null);

        for (CategorySource other : List.of(CategorySource.AUTO, CategorySource.LINK, CategorySource.RULE)) {
            assertFalse(writer.set(t, "travel", null, other, (UUID) null), other + " must not overwrite USER");
            assertFalse(writer.set(t, null, null, other, (UUID) null), other + " must not clear USER");
        }
        assertEquals("food", t.getCategoryKey());
        assertEquals(CategorySource.USER, t.getCategorySource());

        assertTrue(writer.set(t, "travel", null, CategorySource.USER, (UUID) null), "the user can change their own");
        assertEquals("travel", t.getCategoryKey());
    }

    @Test
    void otherSourcesMayOverwriteEachOther() {
        Transaction t = txn(TransactionType.DEBIT);
        writer.set(t, "food", null, CategorySource.AUTO, (UUID) null);
        assertTrue(writer.set(t, "groceries", null, CategorySource.RULE, (UUID) null));
        assertEquals("groceries", t.getCategoryKey());
        assertEquals(CategorySource.RULE, t.getCategorySource());
    }

    @Test
    void ruleSourceKeepsTheOwnersRuleAsAppliedRule_otherSourcesDoNot() {
        CategoryRule r = rule();
        Transaction t = txn(TransactionType.DEBIT);

        writer.set(t, "food", null, CategorySource.RULE, r.getId());
        assertSame(r, t.getAppliedRule());

        writer.set(t, "travel", null, CategorySource.AUTO, r.getId());
        assertNull(t.getAppliedRule(), "only a RULE category is linked to its rule");

        UUID someoneElsesRule = UUID.randomUUID();
        when(ruleRepository.findByUserIdAndIdIn(user.getId(), List.of(someoneElsesRule))).thenReturn(List.of());
        writer.set(t, "food", null, CategorySource.RULE, someoneElsesRule);
        assertNull(t.getAppliedRule(), "a rule that is not the transaction owner's is never linked");
    }

    @Test
    void excludedFollowsTheCategoryDefaultUntilTheUserChoseIt() {
        Transaction t = txn(TransactionType.DEBIT);

        writer.set(t, "self_transfer", null, CategorySource.AUTO, (UUID) null);
        assertTrue(t.isTransactionExcluded());

        writer.set(t, "food", null, CategorySource.AUTO, (UUID) null);
        assertFalse(t.isTransactionExcluded(), "a category that is not excluded by default clears it");

        writer.set(t, "card_bill", null, CategorySource.RULE, (UUID) null);
        assertTrue(t.isTransactionExcluded());
        writer.set(t, null, null, CategorySource.RULE, (UUID) null);
        assertFalse(t.isTransactionExcluded(), "clearing the category clears the default exclusion");

        t.setExcludeSetByUser(true);
        t.setTransactionExcluded(false);
        writer.set(t, "self_transfer", null, CategorySource.USER, (UUID) null);
        assertFalse(t.isTransactionExcluded(), "the user's own is_excluded choice wins over the default");

        t.setTransactionExcluded(true);
        writer.set(t, "food", null, CategorySource.USER, (UUID) null);
        assertTrue(t.isTransactionExcluded(), "and stays, whatever the category");
    }

    @Test
    void clearingByTheUserIsRememberedClearingByOthersIsNot() {
        Transaction t = txn(TransactionType.DEBIT);
        writer.set(t, "food", "food.dining", CategorySource.AUTO, (UUID) null);

        writer.set(t, null, null, CategorySource.RULE, (UUID) null);
        assertNull(t.getCategoryKey());
        assertNull(t.getSubcategoryKey());
        assertNull(t.getCategorySource());
        assertNull(t.getCategorySetAt());

        writer.set(t, "food", null, CategorySource.AUTO, (UUID) null);
        writer.set(t, null, null, CategorySource.USER, (UUID) null);
        assertNull(t.getCategoryKey());
        assertEquals(CategorySource.USER, t.getCategorySource(), "the user's clear is a decision the engine must respect");
        assertFalse(writer.set(t, "food", null, CategorySource.AUTO, (UUID) null));
    }

    @Test
    void validatesAgainstTheCatalogAndLeavesTheTransactionAloneOnFailure() {
        Transaction debit = txn(TransactionType.DEBIT);
        assertThrows(ValidationException.class, () -> writer.set(debit, "salary", null, CategorySource.USER, (UUID) null));
        assertThrows(ValidationException.class, () -> writer.set(debit, "nope", null, CategorySource.AUTO, (UUID) null));
        assertThrows(ValidationException.class, () -> writer.set(debit, "food", "travel.flights", CategorySource.AUTO, (UUID) null));
        assertNull(debit.getCategoryKey());
        assertNull(debit.getCategorySource());

        Transaction credit = txn(TransactionType.CREDIT);
        assertThrows(ValidationException.class, () -> writer.set(credit, "food", null, CategorySource.USER, (UUID) null));
        assertTrue(writer.set(credit, "salary", "salary.bonus", CategorySource.USER, (UUID) null));
        assertFalse(writer.fits(credit, "food", null));
        assertTrue(writer.fits(credit, "gifts", null));
    }

    @Test
    void aLinkedRefundKeepsTheOriginalCategoryEvenWhenItIsMoneyOutOnly() {
        Transaction refund = txn(TransactionType.CREDIT);

        assertTrue(writer.set(refund, "shopping", "shopping.electronics", CategorySource.LINK, (UUID) null));

        assertEquals("shopping", refund.getCategoryKey());
        assertEquals(CategorySource.LINK, refund.getCategorySource());
        assertThrows(ValidationException.class,
                () -> writer.set(refund, "shopping", "nope", CategorySource.LINK, (UUID) null), "keys are still checked");
    }

    @Test
    void restorePutsBackAPreviousStateOverAUserChoice() {
        CategoryRule r = rule();
        Transaction t = txn(TransactionType.DEBIT);
        writer.set(t, "food", null, CategorySource.USER, (UUID) null);
        Instant earlier = Instant.parse("2026-10-01T10:00:00Z");

        writer.restore(t, "groceries", "groceries.dairy", CategorySource.RULE, r.getId(), earlier);

        assertEquals("groceries", t.getCategoryKey());
        assertEquals("groceries.dairy", t.getSubcategoryKey());
        assertEquals(CategorySource.RULE, t.getCategorySource());
        assertEquals(earlier, t.getCategorySetAt());
        assertSame(r, t.getAppliedRule());

        writer.restore(t, null, null, null, null, null);
        assertNull(t.getCategoryKey());
        assertNull(t.getCategorySource());
        assertNull(t.getCategorySetAt());
    }
}
