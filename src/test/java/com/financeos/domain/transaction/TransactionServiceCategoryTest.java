package com.financeos.domain.transaction;

import com.financeos.api.transaction.dto.CreateTransactionRequest;
import com.financeos.api.transaction.dto.UpdateTransactionRequest;
import com.financeos.core.exception.ResourceNotFoundException;
import com.financeos.core.exception.ValidationException;
import com.financeos.core.security.UserContext;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.category.Tag;
import com.financeos.domain.category.TagRepository;
import com.financeos.domain.categorization.CategorizationService;
import com.financeos.domain.user.User;
import com.financeos.domain.user.UserRepository;
import com.financeos.support.TestCategories;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Create/update/set-category rules around the transaction's one category. */
class TransactionServiceCategoryTest {

    private TransactionRepository transactionRepository;
    private AccountRepository accountRepository;
    private TagRepository tagRepository;
    private UserRepository userRepository;
    private TransactionService service;
    private User user;
    private Account account;

    @BeforeEach
    void setUp() {
        transactionRepository = mock(TransactionRepository.class);
        accountRepository = mock(AccountRepository.class);
        tagRepository = mock(TagRepository.class);
        userRepository = mock(UserRepository.class);
        service = new TransactionService(transactionRepository, accountRepository, tagRepository, userRepository,
                new ReviewStatusManager(), mock(CategorizationService.class), null, null, null,
                mock(com.financeos.core.observability.AuditLogger.class), null, null,
                TestCategories.writer(), null);

        UUID uid = UUID.randomUUID();
        UserContext.setCurrentUserId(uid);
        user = new User();
        user.setId(uid);
        account = new Account();
        account.setId(UUID.randomUUID());
        account.setUser(user);

        when(userRepository.getReferenceById(uid)).thenReturn(user);
        when(accountRepository.findById(account.getId())).thenReturn(Optional.of(account));
        when(transactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private CreateTransactionRequest create(String amount, String categoryKey, String subKey) {
        return new CreateTransactionRequest(account.getId(), null, LocalDate.now(), new BigDecimal(amount), "Lunch",
                null, categoryKey, subKey, null, null, null, null, null);
    }

    private Transaction stored(String categoryKey, CategorySource source) {
        Transaction t = new Transaction();
        t.setId(UUID.randomUUID());
        t.setUser(user);
        t.setAccount(account);
        t.setDate(LocalDate.now());
        t.setAmount(new BigDecimal("100.00"));
        t.setType(TransactionType.DEBIT);
        t.setCategoryKey(categoryKey);
        t.setCategorySource(source);
        t.setCategorySetAt(java.time.Instant.parse("2026-10-01T00:00:00Z"));
        when(transactionRepository.findById(t.getId())).thenReturn(Optional.of(t));
        when(transactionRepository.findAllByIdInAndUserId(List.of(t.getId()), user.getId())).thenReturn(List.of(t));
        return t;
    }

    private UpdateTransactionRequest update(String categoryKey, String subKey, Boolean excluded, String description) {
        return new UpdateTransactionRequest(null, null, LocalDate.now(), new BigDecimal("-100.00"), description, null,
                categoryKey, subKey, null, excluded, null, null, null, null, null);
    }

    // ------------------------------------------------------------------ create

    @Test
    void createWithACategoryStoresItAsTheUsersChoice() {
        Transaction t = service.createTransaction(create("-250.00", "food", "food.dining"));

        assertEquals("food", t.getCategoryKey());
        assertEquals("food.dining", t.getSubcategoryKey());
        assertEquals(CategorySource.USER, t.getCategorySource());
        assertNotNull(t.getCategorySetAt());
    }

    @Test
    void createWithoutACategoryLeavesItUncategorised() {
        Transaction t = service.createTransaction(create("-250.00", null, null));

        assertNull(t.getCategoryKey());
        assertNull(t.getCategorySource());
        assertNull(t.getCategorySetAt());
    }

    @Test
    void createRejectsACategoryThatDoesNotFitTheDirection() {
        assertThrows(ValidationException.class, () -> service.createTransaction(create("250.00", "food", null)));
        assertThrows(ValidationException.class, () -> service.createTransaction(create("-250.00", "salary", null)));
    }

    @Test
    void createTakesTagsOnlyFromTheUsersOwn() {
        Tag mine = new Tag("Trip", user);
        mine.setId(UUID.randomUUID());
        Tag theirs = new Tag("Trip", new User());
        theirs.getUser().setId(UUID.randomUUID());
        theirs.setId(UUID.randomUUID());
        when(tagRepository.findAllById(List.of(mine.getId()))).thenReturn(List.of(mine));
        when(tagRepository.findAllById(List.of(theirs.getId()))).thenReturn(List.of(theirs));

        CreateTransactionRequest ok = new CreateTransactionRequest(account.getId(), null, LocalDate.now(),
                new BigDecimal("-5"), "x", List.of(mine.getId()), null, null, null, null, null, null, null);
        assertEquals(1, service.createTransaction(ok).getTags().size());

        CreateTransactionRequest foreign = new CreateTransactionRequest(account.getId(), null, LocalDate.now(),
                new BigDecimal("-5"), "x", List.of(theirs.getId()), null, null, null, null, null, null, null);
        assertThrows(ResourceNotFoundException.class, () -> service.createTransaction(foreign));
    }

    // ------------------------------------------------------------------ update

    @Test
    void editingDescriptionAmountOrAccountNeverRecategorises() {
        Transaction t = stored("food", CategorySource.AUTO);

        service.updateTransaction(t.getId(), update(null, null, null, "A new description"));

        assertEquals("food", t.getCategoryKey());
        assertEquals(CategorySource.AUTO, t.getCategorySource());
        assertEquals(java.time.Instant.parse("2026-10-01T00:00:00Z"), t.getCategorySetAt());
    }

    @Test
    void resendingTheStoredCategoryChangesNothing() {
        Transaction t = stored("food", CategorySource.RULE);

        service.updateTransaction(t.getId(), update("food", null, null, "Desc"));

        assertEquals(CategorySource.RULE, t.getCategorySource(), "an unchanged category is not claimed by the user");
        assertEquals(java.time.Instant.parse("2026-10-01T00:00:00Z"), t.getCategorySetAt());
    }

    @Test
    void aChangedCategoryOrSubcategoryBecomesTheUsersChoice() {
        Transaction t = stored("food", CategorySource.AUTO);

        service.updateTransaction(t.getId(), update("groceries", "groceries.dairy", null, "Desc"));

        assertEquals("groceries", t.getCategoryKey());
        assertEquals("groceries.dairy", t.getSubcategoryKey());
        assertEquals(CategorySource.USER, t.getCategorySource());

        service.updateTransaction(t.getId(), update("groceries", "groceries.bakery", null, "Desc"));
        assertEquals("groceries.bakery", t.getSubcategoryKey());
    }

    @Test
    void anInvalidCategoryOnUpdateIsRejected() {
        Transaction t = stored("food", CategorySource.AUTO);

        assertThrows(ValidationException.class, () -> service.updateTransaction(t.getId(), update("salary", null, null, "Desc")));
    }

    @Test
    void changingIsExcludedMarksItAsTheUsersChoice() {
        Transaction t = stored("food", CategorySource.AUTO);

        service.updateTransaction(t.getId(), update(null, null, false, "Desc"));
        assertFalse(t.isExcludeSetByUser(), "echoing the stored value is not a choice");

        service.updateTransaction(t.getId(), update(null, null, true, "Desc"));
        assertTrue(t.isExcludeSetByUser());
        assertTrue(t.isTransactionExcluded());
    }

    @Test
    void aUserExcludeChoiceSurvivesALaterCategoryChange() {
        Transaction t = stored("food", CategorySource.AUTO);
        service.updateTransaction(t.getId(), update(null, null, true, "Desc"));

        service.updateTransaction(t.getId(), update("groceries", null, true, "Desc"));

        assertEquals("groceries", t.getCategoryKey());
        assertTrue(t.isTransactionExcluded());
    }

    @Test
    void aCategoryChangeFollowsTheExcludedDefaultWhenTheUserNeverChoseIt() {
        Transaction t = stored("food", CategorySource.AUTO);

        service.updateTransaction(t.getId(), update("self_transfer", null, false, "Desc"));
        assertTrue(t.isTransactionExcluded());
        assertFalse(t.isExcludeSetByUser());
    }

    // ------------------------------------------------------------------ setCategory

    @Test
    void setCategoryIsTheUsersChoiceAndReportsThePreviousState() {
        Transaction t = stored("food", CategorySource.AUTO);

        TransactionService.CategoryChange change = service.setCategory(t.getId(), "groceries", "groceries.dairy", null);

        assertEquals("groceries", t.getCategoryKey());
        assertEquals(CategorySource.USER, t.getCategorySource());
        assertEquals("food", change.previousCategoryKey());
        assertEquals(CategorySource.AUTO, change.previousSource());
        assertEquals(java.time.Instant.parse("2026-10-01T00:00:00Z"), change.previousSetAt());
        assertNull(change.previousRuleId());
    }

    @Test
    void setCategoryWithRestorePutsBackThePreviousStateOverTheUsersChoice() {
        Transaction t = stored("food", CategorySource.AUTO);
        service.setCategory(t.getId(), "groceries", null, null);

        java.time.Instant setAt = java.time.Instant.parse("2026-10-01T00:00:00Z");
        service.setCategory(t.getId(), "food", null, new TransactionService.CategoryRestore(CategorySource.AUTO, null, setAt));

        assertEquals("food", t.getCategoryKey());
        assertEquals(CategorySource.AUTO, t.getCategorySource());
        assertEquals(setAt, t.getCategorySetAt());
    }

    @Test
    void setCategoryWithNullKeysClearsAsTheUser() {
        Transaction t = stored("food", CategorySource.AUTO);

        service.setCategory(t.getId(), null, null, null);

        assertNull(t.getCategoryKey());
        assertEquals(CategorySource.USER, t.getCategorySource());
    }

    @Test
    void setCategoryOnAnotherUsersTransactionIs404() {
        UUID id = UUID.randomUUID();
        when(transactionRepository.findAllByIdInAndUserId(List.of(id), user.getId())).thenReturn(List.of());

        assertThrows(ResourceNotFoundException.class, () -> service.setCategory(id, "food", null, null));
    }

    // ------------------------------------------------------------------ ruleKey

    private void withAnalyser(String counterparty) {
        com.financeos.domain.categorization.CategoryAssigner assigner = mock(com.financeos.domain.categorization.CategoryAssigner.class);
        when(assigner.analyse(any(), any(), any(), any())).thenReturn(
                new com.financeos.domain.categorization.builtin.BuiltinMatch(null, null, null, null, counterparty, false));
        org.springframework.test.util.ReflectionTestUtils.setField(service, "categoryAssigner", assigner);
    }

    @Test
    void ruleKeyIsTheUpperCasedCounterpartyTheGroupingUses() {
        withAnalyser("swiggy");
        Transaction t = stored("food", CategorySource.AUTO);
        t.setDescription("UPI/412345678901/SWIGGY ORDER/swiggy@icici");

        assertEquals("SWIGGY", service.ruleKeyOf(t));
    }

    @Test
    void ruleKeyIsNullWhenNoCounterpartyOrNoText() {
        withAnalyser(null);
        Transaction t = stored("food", CategorySource.AUTO);
        t.setDescription("Lunch");
        assertNull(service.ruleKeyOf(t));

        withAnalyser("swiggy");
        t.setDescription(" ");
        t.setSourcedDescription(null);
        assertNull(service.ruleKeyOf(t));
    }

    @Test
    void ruleKeyIsNullWithoutAnAssigner() {
        Transaction t = stored("food", CategorySource.AUTO);
        t.setDescription("Lunch");
        assertNull(service.ruleKeyOf(t));
    }
}
