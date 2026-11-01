package com.financeos.domain.categorization;

import com.financeos.domain.categorization.builtin.BuiltinCategorizer;
import com.financeos.domain.transaction.CategorySource;
import com.financeos.domain.transaction.Transaction;
import com.financeos.domain.transaction.TransactionRepository;
import com.financeos.domain.transaction.TransactionType;
import com.financeos.domain.user.User;
import com.financeos.support.TestCategories;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Rules, then links, then the engine; arrival once; USER never touched; batched queries. */
class CategoryAssignerTest {

    private CategoryRuleRepository ruleRepository;
    private LinkCategories linkCategories;
    private CategoryAssigner assigner;
    private User user;

    @BeforeEach
    void setUp() {
        ruleRepository = mock(CategoryRuleRepository.class);
        linkCategories = mock(LinkCategories.class);
        when(linkCategories.targetsFor(any(Collection.class), org.mockito.ArgumentMatchers.anyBoolean())).thenReturn(Map.of());
        assigner = new CategoryAssigner(ruleRepository, linkCategories, new BuiltinCategorizer(),
                TestCategories.writer(ruleRepository), mock(TransactionRepository.class));
        user = new User();
        user.setId(UUID.randomUUID());
    }

    private Transaction txn(String description, TransactionType type) {
        Transaction t = new Transaction();
        t.setId(UUID.randomUUID());
        t.setUser(user);
        t.setType(type);
        t.setAmount(new BigDecimal("100"));
        t.setSourcedDescription(description);
        return t;
    }

    private CategoryRule rule(String pattern, String category, String sub) {
        CategoryRule r = new CategoryRule();
        r.setId(UUID.randomUUID());
        r.setUser(user);
        r.setMerchantKey(pattern);
        r.setMatchType(MatchType.CONTAINS);
        r.setCategoryKey(category);
        r.setSubcategoryKey(sub);
        r.setUpdatedAt(Instant.now());
        return r;
    }

    @Test
    void engineAnswersWhenNothingElseDoes() {
        Transaction t = txn("SWIGGY", TransactionType.DEBIT);
        assertEquals(1, assigner.assignOnArrival(List.of(t)));
        assertEquals("food", t.getCategoryKey());
        assertEquals("food.delivery", t.getSubcategoryKey());
        assertEquals(CategorySource.AUTO, t.getCategorySource());
        assertNull(t.getAppliedRule());
    }

    @Test
    void aCreditFromAMerchantIsARefund() {
        Transaction t = txn("SWIGGY", TransactionType.CREDIT);
        assigner.assignOnArrival(List.of(t));
        assertEquals("refund", t.getCategoryKey());
        assertEquals(CategorySource.AUTO, t.getCategorySource());
    }

    @Test
    void nothingKnownOrAPersonStaysUncategorised() {
        Transaction unknown = txn("ZYXQWVUT", TransactionType.DEBIT);
        Transaction person = txn("UPI_MR RAVI KUMAR SHARMA", TransactionType.DEBIT);
        assertEquals(0, assigner.assignOnArrival(List.of(unknown, person)));
        assertNull(unknown.getCategoryKey());
        assertNull(unknown.getCategorySource());
        assertNull(person.getCategoryKey());
        assertNull(person.getCategorySource());
    }

    @Test
    void ruleBeatsLinkBeatsEngine() {
        CategoryRule rule = rule("swiggy", "groceries", null);
        when(ruleRepository.findByUserId(user.getId())).thenReturn(List.of(rule));
        Transaction withRule = txn("SWIGGY ORDER", TransactionType.DEBIT);
        Transaction withLink = txn("SWIGGY LINKED", TransactionType.DEBIT);
        Transaction engineOnly = txn("ZOMATO", TransactionType.DEBIT);
        // the link would say self_transfer for both of the first two; only the rule-less one takes it
        when(linkCategories.targetsFor(any(Collection.class), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenReturn(Map.of(withRule.getId(), new LinkCategories.Target("self_transfer", null),
                        withLink.getId(), new LinkCategories.Target("self_transfer", null)));
        rule.setMerchantKey("swiggy order");

        assigner.assignOnArrival(List.of(withRule, withLink, engineOnly));

        assertEquals("groceries", withRule.getCategoryKey());
        assertEquals(CategorySource.RULE, withRule.getCategorySource());
        assertEquals(rule, withRule.getAppliedRule());
        assertEquals("self_transfer", withLink.getCategoryKey());
        assertEquals(CategorySource.LINK, withLink.getCategorySource());
        assertEquals("food.delivery", engineOnly.getSubcategoryKey());
        assertEquals(CategorySource.AUTO, engineOnly.getCategorySource());
    }

    @Test
    void aRuleThatCannotApplyToTheDirectionIsSkippedForTheEngine() {
        // salary is money-in only: a debit never takes it, the engine answers instead
        when(ruleRepository.findByUserId(user.getId())).thenReturn(List.of(rule("swiggy", "salary", null)));
        Transaction t = txn("SWIGGY", TransactionType.DEBIT);
        assigner.assignOnArrival(List.of(t));
        assertEquals("food", t.getCategoryKey());
        assertEquals(CategorySource.AUTO, t.getCategorySource());
    }

    @Test
    void arrivalLeavesRowsThatAlreadyHaveACategoryAlone() {
        Transaction picked = txn("SWIGGY", TransactionType.DEBIT);
        picked.setCategoryKey("travel");
        picked.setCategorySource(CategorySource.USER);
        Transaction cleared = txn("SWIGGY", TransactionType.DEBIT);
        cleared.setCategorySource(CategorySource.USER);
        assertEquals(0, assigner.assignOnArrival(List.of(picked, cleared)));
        assertEquals("travel", picked.getCategoryKey());
        assertNull(cleared.getCategoryKey());
        assertEquals(CategorySource.USER, cleared.getCategorySource());
    }

    @Test
    void recategoriseNeverTouchesUserRowsAndClearsWhenNothingAnswers() {
        Transaction user1 = txn("SWIGGY", TransactionType.DEBIT);
        user1.setCategoryKey("travel");
        user1.setCategorySource(CategorySource.USER);
        Transaction orphan = txn("ZYXQWVUT", TransactionType.DEBIT);
        orphan.setCategoryKey("food");
        orphan.setCategorySource(CategorySource.LINK);
        Transaction fallsToEngine = txn("ZOMATO", TransactionType.DEBIT);
        fallsToEngine.setCategoryKey("self_transfer");
        fallsToEngine.setCategorySource(CategorySource.LINK);

        List<Transaction> changed = assigner.recategorise(List.of(user1, orphan, fallsToEngine), null);

        assertEquals(2, changed.size());
        assertEquals("travel", user1.getCategoryKey());
        assertNull(orphan.getCategoryKey());
        assertNull(orphan.getCategorySource());
        assertEquals("food", fallsToEngine.getCategoryKey());
        assertEquals("food.delivery", fallsToEngine.getSubcategoryKey());
        assertEquals(CategorySource.AUTO, fallsToEngine.getCategorySource());
    }

    @Test
    void recategoriseIgnoresADeletedRuleStillInTheDatabase() {
        CategoryRule gone = rule("swiggy", "groceries", null);
        when(ruleRepository.findByUserId(user.getId())).thenReturn(List.of(gone));
        Transaction t = txn("SWIGGY", TransactionType.DEBIT);
        t.setCategoryKey("groceries");
        t.setCategorySource(CategorySource.RULE);
        t.setAppliedRule(gone);

        assigner.recategorise(List.of(t), gone.getId());

        assertEquals("food", t.getCategoryKey());
        assertEquals(CategorySource.AUTO, t.getCategorySource());
        assertNull(t.getAppliedRule());
    }

    @Test
    void backfillKeepsIsExcludedAndArrivalFollowsTheCategory() {
        Transaction old = txn("SWIGGY", TransactionType.DEBIT);
        old.setTransactionExcluded(true);
        assigner.backfill(List.of(old));
        assertEquals("food", old.getCategoryKey());
        assertTrue(old.isTransactionExcluded(), "backfill leaves is_excluded as it was");

        Transaction fresh = txn("SWIGGY", TransactionType.DEBIT);
        fresh.setTransactionExcluded(true);
        assigner.assignOnArrival(List.of(fresh));
        assertEquals(false, fresh.isTransactionExcluded(), "arrival makes it follow the category (food is counted)");
    }

    @Test
    void oneRulesQueryPerUserAndOneLinkLookupForTheWholeBatch() {
        List<Transaction> batch = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            batch.add(txn(i % 2 == 0 ? "SWIGGY" : "ZYXQ " + i, TransactionType.DEBIT));
        }
        User other = new User();
        other.setId(UUID.randomUUID());
        Transaction otherUsers = txn("UBER", TransactionType.DEBIT);
        otherUsers.setUser(other);
        batch.add(otherUsers);

        assigner.assignOnArrival(batch);

        verify(ruleRepository, times(1)).findByUserId(user.getId());
        verify(ruleRepository, times(1)).findByUserId(other.getId());
        verify(linkCategories, times(1)).targetsFor(any(Collection.class), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void suggestUsesRulesThenEngineAndHonoursTheDirection() {
        when(ruleRepository.findByUserId(user.getId())).thenReturn(List.of(rule("swiggy", "groceries", null)));
        Optional<CategoryAssigner.Suggestion> fromRule = assigner.suggest(user.getId(), "UPI SWIGGY 1", TransactionType.DEBIT);
        assertEquals("groceries", fromRule.orElseThrow().categoryKey());
        assertEquals(CategorySource.RULE, fromRule.get().source());

        Optional<CategoryAssigner.Suggestion> fromEngine = assigner.suggest(user.getId(), "UBER", null);
        assertEquals("transport.cabs", fromEngine.orElseThrow().subcategoryKey());
        assertEquals(CategorySource.AUTO, fromEngine.get().source());

        assertTrue(assigner.suggest(user.getId(), "ZYXQWVUT", TransactionType.DEBIT).isEmpty());
        assertTrue(assigner.suggest(user.getId(), "  ", TransactionType.DEBIT).isEmpty());
    }

    @Test
    void mailContextFeedsTheEngineOnArrivalById() {
        TransactionRepository repo = mock(TransactionRepository.class);
        CategoryAssigner byId = new CategoryAssigner(ruleRepository, linkCategories, new BuiltinCategorizer(),
                TestCategories.writer(ruleRepository), repo);
        // the stored description names nobody the engine knows; the mail's extracted counterparty does
        Transaction withMail = txn("Payment on card ending 1234", TransactionType.DEBIT);
        Transaction withoutMail = txn("Payment on card ending 1234", TransactionType.DEBIT);
        when(repo.findAllByIdInAndUserId(any(), org.mockito.ArgumentMatchers.eq(user.getId())))
                .thenReturn(List.of(withMail, withoutMail));
        int assigned = byId.assignOnArrivalById(user.getId(), List.of(withMail.getId(), withoutMail.getId()),
                Map.of(withMail.getId(), new CategoryAssigner.ArrivalContext("SWIGGY", "alerts@examplebank.test",
                        "Card alert", "Rs 100 spent on your card", "tpl-1")));
        assertEquals(1, assigned);
        assertEquals("food.delivery", withMail.getSubcategoryKey());
        assertEquals(CategorySource.AUTO, withMail.getCategorySource());
        assertNull(withoutMail.getCategoryKey());
    }

    @Test
    void mailContextWithBlankFieldsFallsBackToTheDescription() {
        TransactionRepository repo = mock(TransactionRepository.class);
        CategoryAssigner byId = new CategoryAssigner(ruleRepository, linkCategories, new BuiltinCategorizer(),
                TestCategories.writer(ruleRepository), repo);
        Transaction t = txn("POS 4111XXXXXXXX1234 SWIGGY BLR", TransactionType.DEBIT);
        when(repo.findAllByIdInAndUserId(any(), org.mockito.ArgumentMatchers.eq(user.getId()))).thenReturn(List.of(t));
        byId.assignOnArrivalById(user.getId(), List.of(t.getId()),
                Map.of(t.getId(), new CategoryAssigner.ArrivalContext(" ", null, null, null, null)));
        assertEquals("food.delivery", t.getSubcategoryKey());
    }
}
