package com.financeos.domain.account;

import com.financeos.api.account.dto.AccountResponse;
import com.financeos.api.account.dto.CardCycleSummaryResponse;
import com.financeos.api.account.dto.CreateAccountRequest;
import com.financeos.api.account.dto.CreditLimitPoolRequest;
import com.financeos.api.account.dto.CreditLimitPoolResponse;
import com.financeos.core.exception.ResourceNotFoundException;
import com.financeos.core.exception.ValidationException;
import com.financeos.core.security.UserContext;
import com.financeos.domain.account.card.CardRepository;
import com.financeos.domain.account.card.CardholderRepository;
import com.financeos.domain.holding.HoldingValuationService;
import com.financeos.domain.statement.Statement;
import com.financeos.domain.statement.StatementCreditCardDetails;
import com.financeos.domain.statement.StatementRepository;
import com.financeos.domain.transaction.TransactionRepository;
import com.financeos.domain.user.User;
import com.financeos.domain.user.UserRepository;
import com.financeos.gmail.domain.GmailBackfillDemandRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Shared credit limits: several credit card accounts drawing on one pool. Covers joining, creating,
 * renaming and re-limiting a pool through the account request, the validation around it, pruning
 * empty pools, and utilisation measured on the pool on every read path.
 */
class AccountCreditLimitPoolTest {

    private AccountRepository accountRepository;
    private CardholderRepository cardholderRepository;
    private StatementRepository statementRepository;
    private TransactionRepository transactionRepository;
    private UserRepository userRepository;
    private AccountService service;
    private User user;

    @BeforeEach
    void setUp() {
        accountRepository = mock(AccountRepository.class);
        cardholderRepository = mock(CardholderRepository.class);
        statementRepository = mock(StatementRepository.class);
        transactionRepository = mock(TransactionRepository.class);
        userRepository = mock(UserRepository.class);
        service = new AccountService(accountRepository, cardholderRepository, mock(CardRepository.class),
                userRepository, statementRepository, transactionRepository,
                mock(HoldingValuationService.class), mock(GmailBackfillDemandRepository.class),
                mock(ApplicationEventPublisher.class));
        user = new User();
        user.setId(UUID.randomUUID());
        UserContext.setCurrentUserId(user.getId());
        when(userRepository.getReferenceById(user.getId())).thenReturn(user);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    // ------------------------------------------------------------------ fixtures

    private CreditLimitPool pool(String name, String limit) {
        CreditLimitPool p = new CreditLimitPool(user, name, new BigDecimal(limit));
        p.setId(UUID.randomUUID());
        return p;
    }

    private Account card(String ownLimit, CreditLimitPool pool) {
        Account a = new Account("Card", AccountType.credit_card);
        a.setId(UUID.randomUUID());
        a.setUser(user);
        AccountCreditCardDetails d = new AccountCreditCardDetails(a, ownLimit == null ? null : new BigDecimal(ownLimit), null);
        d.setLimitPool(pool);
        a.setCreditCardDetails(d);
        return a;
    }

    private Account newCard() {
        Account a = new Account("New card", AccountType.credit_card);
        a.setUser(user);
        return a;
    }

    private static CreateAccountRequest.CreditCardRequest request(String ownLimit, CreditLimitPoolRequest limitPool) {
        return new CreateAccountRequest.CreditCardRequest("Card", AccountType.credit_card, null, null, null,
                "1234", ownLimit == null ? null : new BigDecimal(ownLimit), LocalDate.of(2026, 1, 1), null, null,
                null, null, null, limitPool);
    }

    private static CreditLimitPoolRequest newPool(String name, String limit) {
        return new CreditLimitPoolRequest(null, name, limit == null ? null : new BigDecimal(limit));
    }

    private static AccountRepositoryCustom.AccountBalanceBatch owes(Account a, String totalSum) {
        return new AccountRepositoryCustom.AccountBalanceBatch(a.getId(), null, null, new BigDecimal(totalSum), null, null);
    }

    // ------------------------------------------------------------------ own limit rules

    @Test
    void anUnpooledCardStillNeedsItsOwnLimit() {
        ValidationException e = assertThrows(ValidationException.class,
                () -> service.addCreditCardDetails(newCard(), request(null, null)));
        assertEquals("Credit limit is required", e.getMessage());
    }

    @Test
    void anUnpooledCardWithALimitHasNoPool() {
        Account account = newCard();
        service.addCreditCardDetails(account, request("100000", null));

        assertNull(account.getCreditCardDetails().getLimitPool());
        assertEquals(new BigDecimal("100000"), account.getCreditCardDetails().getCreditLimit());
    }

    // ------------------------------------------------------------------ creating a pool

    @Test
    void aNewPoolIsCreatedForTheOwnerWithATrimmedNameAndTheCardNeedsNoLimitOfItsOwn() {
        Account account = newCard();
        service.addCreditCardDetails(account, request(null, newPool("  HDFC shared  ", "500000")));

        CreditLimitPool created = account.getCreditCardDetails().getLimitPool();
        assertNotNull(created);
        assertNull(created.getId(), "persisted with the account by cascade");
        assertSame(user, created.getUser());
        assertEquals("HDFC shared", created.getName());
        assertEquals(new BigDecimal("500000"), created.getCreditLimit());
        assertNull(account.getCreditCardDetails().getCreditLimit());
        verify(accountRepository, never()).findOwnedLimitPool(any(), any());
    }

    @Test
    void aNewPoolNeedsAName() {
        for (String name : new String[]{null, "", "   "}) {
            ValidationException e = assertThrows(ValidationException.class,
                    () -> service.addCreditCardDetails(newCard(), request(null, newPool(name, "500000"))));
            assertEquals("Shared limit name is required", e.getMessage());
        }
    }

    @Test
    void aNewPoolNameIsAtMost150Characters() {
        assertNotNull(assertThrowsNothing(() -> service.addCreditCardDetails(newCard(), request(null, newPool("x".repeat(150), "1")))));
        ValidationException e = assertThrows(ValidationException.class,
                () -> service.addCreditCardDetails(newCard(), request(null, newPool("x".repeat(151), "500000"))));
        assertEquals("Shared limit name must be at most 150 characters", e.getMessage());
    }

    @Test
    void aNewPoolNeedsAPositiveLimit() {
        for (String limit : new String[]{null, "0", "-1"}) {
            ValidationException e = assertThrows(ValidationException.class,
                    () -> service.addCreditCardDetails(newCard(), request(null, newPool("HDFC", limit))));
            assertEquals("Shared credit limit must be greater than zero", e.getMessage());
        }
    }

    // ------------------------------------------------------------------ joining a pool

    @Test
    void joiningMyPoolLeavesItsNameAndLimitAloneWhenTheRequestSaysNothing() {
        CreditLimitPool mine = pool("HDFC", "500000");
        when(accountRepository.findOwnedLimitPool(mine.getId(), user.getId())).thenReturn(Optional.of(mine));
        Account account = newCard();

        service.addCreditCardDetails(account, request(null, new CreditLimitPoolRequest(mine.getId(), null, null)));

        assertSame(mine, account.getCreditCardDetails().getLimitPool());
        assertEquals("HDFC", mine.getName());
        assertEquals(new BigDecimal("500000"), mine.getCreditLimit());
    }

    @Test
    void joiningMyPoolCanRenameItAndChangeItsLimit() {
        CreditLimitPool mine = pool("HDFC", "500000");
        when(accountRepository.findOwnedLimitPool(mine.getId(), user.getId())).thenReturn(Optional.of(mine));

        service.addCreditCardDetails(newCard(),
                request(null, new CreditLimitPoolRequest(mine.getId(), " HDFC Bank ", new BigDecimal("650000"))));

        assertEquals("HDFC Bank", mine.getName());
        assertEquals(new BigDecimal("650000"), mine.getCreditLimit());
    }

    @Test
    void renamingOrReLimitingAPoolIsValidatedLikeCreatingOne() {
        CreditLimitPool mine = pool("HDFC", "500000");
        when(accountRepository.findOwnedLimitPool(mine.getId(), user.getId())).thenReturn(Optional.of(mine));

        assertEquals("Shared limit name is required", assertThrows(ValidationException.class,
                () -> service.addCreditCardDetails(newCard(), request(null, new CreditLimitPoolRequest(mine.getId(), " ", null)))).getMessage());
        assertEquals("Shared credit limit must be greater than zero", assertThrows(ValidationException.class,
                () -> service.addCreditCardDetails(newCard(), request(null, new CreditLimitPoolRequest(mine.getId(), null, BigDecimal.ZERO)))).getMessage());
    }

    @Test
    void anotherUsersPoolReadsAsNotFound() {
        UUID theirs = UUID.randomUUID();
        when(accountRepository.findOwnedLimitPool(theirs, user.getId())).thenReturn(Optional.empty());
        Account account = newCard();

        assertThrows(ResourceNotFoundException.class,
                () -> service.addCreditCardDetails(account, request(null, new CreditLimitPoolRequest(theirs, null, null))));
        verify(accountRepository).findOwnedLimitPool(theirs, user.getId());
    }

    @Test
    void aPooledCardsOwnLimitIsACapThatCannotExceedThePool() {
        CreditLimitPool mine = pool("HDFC", "500000");
        when(accountRepository.findOwnedLimitPool(mine.getId(), user.getId())).thenReturn(Optional.of(mine));
        CreditLimitPoolRequest join = new CreditLimitPoolRequest(mine.getId(), null, null);

        ValidationException e = assertThrows(ValidationException.class,
                () -> service.addCreditCardDetails(newCard(), request("500001", join)));
        assertEquals("An account's own limit cannot be more than the shared limit", e.getMessage());

        Account equal = newCard();
        service.addCreditCardDetails(equal, request("500000", join));
        assertEquals(new BigDecimal("500000"), equal.getCreditCardDetails().getCreditLimit());
        assertSame(mine, equal.getCreditCardDetails().getLimitPool());
    }

    @Test
    void theCapIsCheckedAgainstTheLimitTheSameRequestSets() {
        CreditLimitPool mine = pool("HDFC", "500000");
        when(accountRepository.findOwnedLimitPool(mine.getId(), user.getId())).thenReturn(Optional.of(mine));

        assertThrows(ValidationException.class, () -> service.addCreditCardDetails(newCard(),
                request("400000", new CreditLimitPoolRequest(mine.getId(), null, new BigDecimal("300000")))));
    }

    // ------------------------------------------------------------------ leaving a pool, pruning

    @Test
    void updatingACardWithoutAPoolTakesItOutAndPrunesEmptyPools() {
        CreditLimitPool old = pool("HDFC", "500000");
        Account account = card(null, old);
        when(accountRepository.findById(account.getId())).thenReturn(Optional.of(account));
        when(accountRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.updateAccount(account.getId(), request("200000", null));

        assertNull(account.getCreditCardDetails().getLimitPool());
        assertEquals(new BigDecimal("200000"), account.getCreditCardDetails().getCreditLimit());
        verify(accountRepository).deleteEmptyLimitPools(user.getId());
    }

    @Test
    void switchingToAnotherPoolPrunesTheOldOne() {
        CreditLimitPool old = pool("HDFC old", "500000");
        CreditLimitPool next = pool("HDFC new", "800000");
        Account account = card(null, old);
        when(accountRepository.findById(account.getId())).thenReturn(Optional.of(account));
        when(accountRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(accountRepository.findOwnedLimitPool(next.getId(), user.getId())).thenReturn(Optional.of(next));
        when(accountRepository.findLimitPoolMembers(next.getId(), user.getId())).thenReturn(List.of(account));

        service.updateAccount(account.getId(), request(null, new CreditLimitPoolRequest(next.getId(), null, null)));

        assertSame(next, account.getCreditCardDetails().getLimitPool());
        verify(accountRepository).deleteEmptyLimitPools(user.getId());
    }

    @Test
    void deletingACardPrunesEmptyPools() {
        Account account = card(null, pool("HDFC", "500000"));
        when(accountRepository.findById(account.getId())).thenReturn(Optional.of(account));

        service.deleteAccount(account.getId());

        verify(accountRepository).delete(account);
        verify(accountRepository).deleteEmptyLimitPools(user.getId());
    }

    @Test
    void creatingACardNeverPrunes() {
        when(accountRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.createAccount(request(null, newPool("HDFC", "500000")));

        verify(accountRepository, never()).deleteEmptyLimitPools(any());
    }

    // ------------------------------------------------------------------ list path

    @Test
    void theListMeasuresPooledCardsOnThePoolAndLeavesOthersAlone() {
        CreditLimitPool hdfc = pool("HDFC", "500000");
        Account a = card(null, hdfc);          // owes 1,00,000
        Account b = card("200000", hdfc);      // owes 50,000; its own cap is not the divisor
        Account solo = card("100000", null);   // owes 30,000 on its own limit
        when(accountRepository.findAll()).thenReturn(List.of(a, b, solo));
        Map<UUID, AccountRepositoryCustom.AccountBalanceBatch> rows = new HashMap<>();
        rows.put(a.getId(), owes(a, "-100000"));
        rows.put(b.getId(), owes(b, "-50000"));
        rows.put(solo.getId(), owes(solo, "-30000"));
        when(accountRepository.findAccountBalanceBatches(anyList())).thenReturn(rows);

        service.getAllAccounts();

        for (Account member : List.of(a, b)) {
            assertEquals(new BigDecimal("30.0"), member.getUtilizationPct(), "1,50,000 of the shared 5,00,000");
            assertEquals(new BigDecimal("500000"), member.getEffectiveCreditLimit());
            CreditLimitPoolUsage usage = member.getLimitPoolUsage();
            assertEquals(hdfc.getId(), usage.id());
            assertEquals("HDFC", usage.name());
            assertEquals(new BigDecimal("150000"), usage.owed());
            assertEquals(new BigDecimal("350000"), usage.available());
            assertEquals(2, usage.memberCount());
            assertEquals(2, usage.openMemberCount());
        }
        assertSame(a.getLimitPoolUsage(), b.getLimitPoolUsage(), "one usage per pool");
        assertEquals(new BigDecimal("30.0"), solo.getUtilizationPct());
        assertEquals(new BigDecimal("100000"), solo.getEffectiveCreditLimit());
        assertNull(solo.getLimitPoolUsage());
        verifyNoInteractions(statementRepository);
        verify(transactionRepository).findLastTransactionDatesByAccountIds(org.mockito.ArgumentMatchers.anyCollection());
        org.mockito.Mockito.verifyNoMoreInteractions(transactionRepository);
        verify(accountRepository, never()).findLimitPoolMembers(any(), any());
    }

    @Test
    void aClosedMembersUnpaidBalanceStillCountsButItIsNotAnOpenMember() {
        CreditLimitPool hdfc = pool("HDFC", "100000");
        Account open = card(null, hdfc);
        Account closed = card(null, hdfc);
        closed.setClosedOn(LocalDate.of(2020, 1, 1));
        when(accountRepository.findAll()).thenReturn(List.of(open, closed));
        when(accountRepository.findAccountBalanceBatches(anyList()))
                .thenReturn(Map.of(open.getId(), owes(open, "-10000"), closed.getId(), owes(closed, "-5000")));

        service.getAllAccounts();

        CreditLimitPoolUsage usage = open.getLimitPoolUsage();
        assertEquals(new BigDecimal("15000"), usage.owed());
        assertEquals(2, usage.memberCount());
        assertEquals(1, usage.openMemberCount());
        assertEquals(new BigDecimal("15.0"), closed.getUtilizationPct());
    }

    @Test
    void aMemberInCreditDoesNotReduceWhatTheOthersOwe() {
        CreditLimitPool hdfc = pool("HDFC", "100000");
        Account owing = card(null, hdfc);
        Account inCredit = card(null, hdfc);
        when(accountRepository.findAll()).thenReturn(List.of(owing, inCredit));
        when(accountRepository.findAccountBalanceBatches(anyList()))
                .thenReturn(Map.of(owing.getId(), owes(owing, "-20000"), inCredit.getId(), owes(inCredit, "7000")));

        service.getAllAccounts();

        assertEquals(new BigDecimal("20000"), owing.getLimitPoolUsage().owed());
        assertEquals(new BigDecimal("20.0"), inCredit.getUtilizationPct());
    }

    @Test
    void twoPoolsAreMeasuredSeparately() {
        CreditLimitPool hdfc = pool("HDFC", "100000");
        CreditLimitPool icici = pool("ICICI", "200000");
        Account h = card(null, hdfc);
        Account i = card(null, icici);
        when(accountRepository.findAll()).thenReturn(List.of(h, i));
        when(accountRepository.findAccountBalanceBatches(anyList()))
                .thenReturn(Map.of(h.getId(), owes(h, "-10000"), i.getId(), owes(i, "-10000")));

        service.getAllAccounts();

        assertEquals(new BigDecimal("10.0"), h.getUtilizationPct());
        assertEquals(new BigDecimal("5.0"), i.getUtilizationPct());
        assertEquals(1, h.getLimitPoolUsage().memberCount());
    }

    // ------------------------------------------------------------------ single-account path

    @Test
    void oneAccountIsMeasuredOnThePoolWithTheOtherMembersFromOneBatchQuery() {
        CreditLimitPool hdfc = pool("HDFC", "500000");
        Account me = card(null, hdfc);
        Account sibling = card(null, hdfc);
        when(accountRepository.findById(me.getId())).thenReturn(Optional.of(me));
        when(transactionRepository.findTotalTransactionSumByAccountId(me.getId())).thenReturn(new BigDecimal("-100000"));
        when(accountRepository.findLimitPoolMembers(hdfc.getId(), user.getId())).thenReturn(List.of(me, sibling));
        when(accountRepository.findAccountBalanceBatches(List.of(sibling.getId())))
                .thenReturn(Map.of(sibling.getId(), owes(sibling, "-50000")));

        Account result = service.getAccountById(me.getId());

        assertEquals(new BigDecimal("30.0"), result.getUtilizationPct());
        assertEquals(new BigDecimal("500000"), result.getEffectiveCreditLimit());
        assertEquals(new BigDecimal("150000"), result.getLimitPoolUsage().owed());
        assertEquals(2, result.getLimitPoolUsage().memberCount());
        verify(statementRepository, never()).findLatestCreditLimits(any(), any());
    }

    @Test
    void theOnlyMemberOfAPoolNeedsNoBatchQuery() {
        CreditLimitPool hdfc = pool("HDFC", "500000");
        Account me = card(null, hdfc);
        when(accountRepository.findById(me.getId())).thenReturn(Optional.of(me));
        when(transactionRepository.findTotalTransactionSumByAccountId(me.getId())).thenReturn(new BigDecimal("-50000"));
        when(accountRepository.findLimitPoolMembers(hdfc.getId(), user.getId())).thenReturn(List.of(me));

        assertEquals(new BigDecimal("10.0"), service.getAccountById(me.getId()).getUtilizationPct());
        verify(accountRepository, never()).findAccountBalanceBatches(anyList());
    }

    @Test
    void anUnpooledAccountNeverLooksForPoolMembers() {
        Account solo = card("100000", null);
        when(accountRepository.findById(solo.getId())).thenReturn(Optional.of(solo));
        when(transactionRepository.findTotalTransactionSumByAccountId(solo.getId())).thenReturn(new BigDecimal("-1000"));

        Account result = service.getAccountById(solo.getId());

        assertNull(result.getLimitPoolUsage());
        verify(accountRepository, never()).findLimitPoolMembers(any(), any());
    }

    @Test
    void theCycleSummaryShowsThePoolLimitAndPoolUtilisation() {
        CreditLimitPool hdfc = pool("HDFC", "400000");
        Account me = card(null, hdfc);
        Account sibling = card(null, hdfc);
        when(accountRepository.findById(me.getId())).thenReturn(Optional.of(me));
        when(transactionRepository.findTotalTransactionSumByAccountId(me.getId())).thenReturn(new BigDecimal("-60000"));
        when(accountRepository.findLimitPoolMembers(hdfc.getId(), user.getId())).thenReturn(List.of(me, sibling));
        when(accountRepository.findAccountBalanceBatches(List.of(sibling.getId())))
                .thenReturn(Map.of(sibling.getId(), owes(sibling, "-40000")));
        Statement s = new Statement();
        s.setId(UUID.randomUUID());
        s.setPeriodEnd(LocalDate.of(2026, 9, 30));
        StatementCreditCardDetails d = new StatementCreditCardDetails();
        d.setCreditLimit(new BigDecimal("400000"));
        d.setAvailableCreditLimit(new BigDecimal("300000"));
        s.setCreditCardDetails(d);
        when(statementRepository.findByAccountIdOrderByPeriodEndAsc(me.getId())).thenReturn(List.of(s));

        CardCycleSummaryResponse summary = service.getCardCycleSummary(me.getId());

        assertEquals(new BigDecimal("400000"), summary.creditLimit());
        assertEquals(new BigDecimal("25.0"), summary.utilizationPct());
        assertEquals(new BigDecimal("300000"), summary.availableCreditLimit(), "the bank's own figure, untouched");
    }

    // ------------------------------------------------------------------ bill digest limit, usage maths, response

    @Test
    void theBillDigestLimitOfAPooledCardIsThePoolsAndNeedsNoStatement() {
        Account me = card("100000", pool("HDFC", "500000"));

        assertEquals(new BigDecimal("500000"), CardUtilization.creditLimit(me, statementRepository));
        verifyNoInteractions(statementRepository);
    }

    @Test
    void availableNeverGoesNegativeAndUtilisationCanPassOneHundred() {
        CreditLimitPoolUsage over = new CreditLimitPoolUsage(UUID.randomUUID(), "HDFC", new BigDecimal("100000"),
                new BigDecimal("120000"), 2, 2);

        assertEquals(BigDecimal.ZERO, over.available());
        assertEquals(new BigDecimal("120.0"), over.utilizationPct());
    }

    @Test
    void theAccountResponseCarriesThePoolOnlyForPooledCards() {
        CreditLimitPool hdfc = pool("HDFC", "500000");
        Account pooled = card(null, hdfc);
        pooled.setCalculatedBalance(new BigDecimal("-50000"));
        pooled.setLimitPoolUsage(CardUtilization.poolUsage(hdfc, List.of(pooled)));
        Account solo = card("100000", null);
        solo.setCalculatedBalance(BigDecimal.ZERO);

        CreditLimitPoolResponse limitPool = ((AccountResponse.CreditCardAccountResponse) AccountResponse.from(pooled)).limitPool();
        assertNotNull(limitPool);
        assertEquals(hdfc.getId(), limitPool.id());
        assertEquals("HDFC", limitPool.name());
        assertEquals(new BigDecimal("500000"), limitPool.creditLimit());
        assertEquals(new BigDecimal("50000"), limitPool.owed());
        assertEquals(new BigDecimal("450000"), limitPool.available());
        assertEquals(new BigDecimal("10.0"), limitPool.utilizationPct());
        assertEquals(1, limitPool.memberCount());
        assertEquals(1, limitPool.openMemberCount());
        assertNull(((AccountResponse.CreditCardAccountResponse) AccountResponse.from(solo)).limitPool());
        assertNull(CreditLimitPoolResponse.from(null));
    }

    private static Object assertThrowsNothing(Runnable r) {
        r.run();
        return Boolean.TRUE;
    }
}
