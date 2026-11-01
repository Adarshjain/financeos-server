package com.financeos.domain.categorization;

import com.financeos.domain.categorization.builtin.BuiltinCategorizer;
import com.financeos.domain.transaction.CategorySource;
import com.financeos.domain.transaction.Transaction;
import com.financeos.domain.transaction.TransactionRepository;
import com.financeos.domain.transaction.TransactionType;
import com.financeos.domain.transaction.link.LinkType;
import com.financeos.domain.transaction.link.TransactionLink;
import com.financeos.domain.transaction.link.TransactionLinkMember;
import com.financeos.domain.user.User;
import com.financeos.support.TestCategories;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** A link made later sets the category only over none/AUTO; an unlink sends LINK rows back through the steps. */
class CategoryLinkHooksTest {

    private TransactionRepository transactionRepository;
    private LinkCategories linkCategories;
    private CategoryRuleRepository ruleRepository;
    private CategoryLinkHooks hooks;
    private User user;

    @BeforeEach
    void setUp() {
        transactionRepository = mock(TransactionRepository.class);
        linkCategories = mock(LinkCategories.class);
        ruleRepository = mock(CategoryRuleRepository.class);
        when(linkCategories.targetsFor(any(Collection.class), anyBoolean())).thenReturn(Map.of());
        var writer = TestCategories.writer(ruleRepository);
        var assigner = new CategoryAssigner(ruleRepository, linkCategories, new BuiltinCategorizer(), writer, transactionRepository);
        hooks = new CategoryLinkHooks(new LinkCategories(null, null, null, null), assigner, writer, transactionRepository);
        user = new User();
        user.setId(UUID.randomUUID());
    }

    private Transaction txn(TransactionType type, CategorySource source, String category) {
        Transaction t = new Transaction();
        t.setId(UUID.randomUUID());
        t.setUser(user);
        t.setType(type);
        t.setSourcedDescription("ZYXQWVUT");
        t.setCategorySource(source);
        t.setCategoryKey(category);
        return t;
    }

    private static TransactionLink link(LinkType type, Transaction anchor, Transaction other) {
        TransactionLink l = new TransactionLink();
        l.setId(UUID.randomUUID());
        l.setType(type);
        l.getMembers().add(new TransactionLinkMember(l, anchor, true));
        l.getMembers().add(new TransactionLinkMember(l, other, false));
        return l;
    }

    @Test
    void transferOverridesNoneAndAutoButNeverRuleOrUser() {
        Transaction none = txn(TransactionType.DEBIT, null, null);
        Transaction auto = txn(TransactionType.CREDIT, CategorySource.AUTO, "refund");
        hooks.linked(link(LinkType.TRANSFER, none, auto), false);
        assertEquals("self_transfer", none.getCategoryKey());
        assertEquals(CategorySource.LINK, none.getCategorySource());
        assertEquals("self_transfer", auto.getCategoryKey());
        assertEquals(CategorySource.LINK, auto.getCategorySource());

        Transaction rule = txn(TransactionType.DEBIT, CategorySource.RULE, "food");
        Transaction picked = txn(TransactionType.CREDIT, CategorySource.USER, "salary");
        hooks.linked(link(LinkType.TRANSFER, rule, picked), false);
        assertEquals("food", rule.getCategoryKey());
        assertEquals(CategorySource.RULE, rule.getCategorySource());
        assertEquals("salary", picked.getCategoryKey());
        assertEquals(CategorySource.USER, picked.getCategorySource());
    }

    @Test
    void cardPaymentAndFeeAndEmiSetTheirCategories() {
        Transaction bank = txn(TransactionType.DEBIT, null, null);
        Transaction card = txn(TransactionType.CREDIT, null, null);
        hooks.linked(link(LinkType.CC_PAYMENT, bank, card), false);
        assertEquals("card_bill", bank.getCategoryKey());
        assertEquals("card_bill.credit_card", bank.getSubcategoryKey());
        assertEquals("card_bill", card.getCategoryKey());
        assertNull(card.getSubcategoryKey());

        Transaction parent = txn(TransactionType.DEBIT, null, null);
        Transaction fee = txn(TransactionType.DEBIT, CategorySource.AUTO, "shopping");
        hooks.linked(link(LinkType.FEE, parent, fee), false);
        assertEquals("fees", fee.getCategoryKey());
        assertNull(parent.getCategoryKey());

        Transaction purchase = txn(TransactionType.DEBIT, null, null);
        Transaction installment = txn(TransactionType.DEBIT, null, null);
        hooks.linked(link(LinkType.EMI, purchase, installment), false);
        assertEquals("emi", installment.getCategoryKey());
    }

    @Test
    void aRecordLinkSetsTheRowsCategoryOverAutoNotOverRuleOrUser() {
        Transaction auto = txn(TransactionType.DEBIT, CategorySource.AUTO, "food");
        Transaction rule = txn(TransactionType.DEBIT, CategorySource.RULE, "food");
        Transaction picked = txn(TransactionType.DEBIT, CategorySource.USER, "food");
        for (Transaction t : List.of(auto, rule, picked)) {
            when(linkCategories.targetsFor(List.of(t), true)).thenReturn(Map.of(t.getId(), new LinkCategories.Target("lent", null)));
        }
        var writer = TestCategories.writer(ruleRepository);
        var real = new CategoryLinkHooks(linkCategories, mock(CategoryAssigner.class), writer, transactionRepository);
        real.linked(auto);
        real.linked(rule);
        real.linked(picked);
        assertEquals("lent", auto.getCategoryKey());
        assertEquals(CategorySource.LINK, auto.getCategorySource());
        assertEquals("food", rule.getCategoryKey());
        assertEquals("food", picked.getCategoryKey());
    }

    @Test
    void unlinkingSendsOnlyLinkSetRowsBackThroughTheSteps() {
        Transaction viaLink = txn(TransactionType.DEBIT, CategorySource.LINK, "self_transfer");
        Transaction viaRule = txn(TransactionType.CREDIT, CategorySource.RULE, "salary");
        Transaction viaUser = txn(TransactionType.CREDIT, CategorySource.USER, "salary");
        hooks.released(List.of(viaLink, viaRule, viaUser));
        // nothing else answers for "ZYXQWVUT": the link row is cleared, the others are untouched
        assertNull(viaLink.getCategoryKey());
        assertNull(viaLink.getCategorySource());
        assertEquals("salary", viaRule.getCategoryKey());
        assertEquals("salary", viaUser.getCategoryKey());
        verify(transactionRepository).saveAll(List.of(viaLink));
    }

    @Test
    void unlinkingARowTheEngineKnowsFallsBackToTheEngine() {
        Transaction t = txn(TransactionType.DEBIT, CategorySource.LINK, "self_transfer");
        t.setSourcedDescription("SWIGGY");
        hooks.released(List.of(t));
        assertEquals("food", t.getCategoryKey());
        assertEquals("food.delivery", t.getSubcategoryKey());
        assertEquals(CategorySource.AUTO, t.getCategorySource());
    }

    @Test
    void releasingRowsNoLinkSetDoesNothing() {
        hooks.released(List.of(txn(TransactionType.DEBIT, null, null), txn(TransactionType.DEBIT, CategorySource.AUTO, "food")));
        verify(transactionRepository, never()).saveAll(any());
    }
}
