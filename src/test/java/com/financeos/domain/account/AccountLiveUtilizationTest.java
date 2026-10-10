package com.financeos.domain.account;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.financeos.api.account.dto.AccountResponse;
import com.financeos.api.account.dto.CardCycleSummaryResponse;
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
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

/** AccountService fills each credit card's live utilisation on the batch and single-account paths. */
class AccountLiveUtilizationTest {

    private AccountRepository accountRepository;
    private CardholderRepository cardholderRepository;
    private StatementRepository statementRepository;
    private TransactionRepository transactionRepository;
    private AccountService service;
    private User user;

    @BeforeEach
    void setUp() {
        accountRepository = mock(AccountRepository.class);
        cardholderRepository = mock(CardholderRepository.class);
        statementRepository = mock(StatementRepository.class);
        transactionRepository = mock(TransactionRepository.class);
        service = new AccountService(accountRepository, cardholderRepository, mock(CardRepository.class),
                mock(UserRepository.class), statementRepository, transactionRepository,
                mock(HoldingValuationService.class), mock(GmailBackfillDemandRepository.class),
                mock(ApplicationEventPublisher.class));
        user = new User();
        user.setId(UUID.randomUUID());
        UserContext.setCurrentUserId(user.getId());
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private Account account(AccountType type, String limit) {
        Account a = new Account(type.name(), type);
        a.setId(UUID.randomUUID());
        a.setUser(user);
        if (limit != null) {
            a.setCreditCardDetails(new AccountCreditCardDetails(a, new BigDecimal(limit), null));
        }
        return a;
    }

    private static AccountRepositoryCustom.AccountBalanceBatch batch(Account a, String closing, String post, String statementLimit) {
        return new AccountRepositoryCustom.AccountBalanceBatch(a.getId(), LocalDate.of(2026, 9, 30),
                closing == null ? null : new BigDecimal(closing), BigDecimal.ZERO, new BigDecimal(post),
                statementLimit == null ? null : new BigDecimal(statementLimit));
    }

    // ------------------------------------------------------------------ batch path

    @Test
    void getAllAccountsUsesTheBatchRowForBalanceAndStatementLimitWithNoPerCardQuery() {
        Account fallback = account(AccountType.credit_card, null);       // owes 1,000 + 200 against the statement's 1,00,000
        Account ownLimit = account(AccountType.credit_card, "200000");   // owes 48,250 against its own 2,00,000
        Account inCredit = account(AccountType.credit_card, "100000");   // statement shows -500 (in credit)
        Account noLimit = account(AccountType.credit_card, null);        // no limit anywhere
        Account bank = account(AccountType.bank_account, null);
        when(accountRepository.findAll()).thenReturn(List.of(fallback, ownLimit, inCredit, noLimit, bank));
        when(cardholderRepository.findByAccountIdInWithCards(anyList())).thenReturn(List.of());
        Map<UUID, AccountRepositoryCustom.AccountBalanceBatch> rows = new HashMap<>();
        rows.put(fallback.getId(), batch(fallback, "1000", "-200", "100000"));
        rows.put(ownLimit.getId(), batch(ownLimit, "48250", "0", "50000"));
        rows.put(inCredit.getId(), batch(inCredit, "-500", "0", null));
        rows.put(noLimit.getId(), batch(noLimit, "900", "0", null));
        rows.put(bank.getId(), batch(bank, "1000", "0", "70000"));
        when(accountRepository.findAccountBalanceBatches(anyList())).thenReturn(rows);

        service.getAllAccounts();

        assertEquals(new BigDecimal("1.2"), fallback.getUtilizationPct());
        assertEquals(new BigDecimal("24.1"), ownLimit.getUtilizationPct(), "the card's own limit wins");
        assertEquals(new BigDecimal("0.0"), inCredit.getUtilizationPct());
        assertNull(noLimit.getUtilizationPct());
        assertNull(bank.getUtilizationPct(), "only cards have utilisation");
        verifyNoInteractions(statementRepository, transactionRepository);
    }

    @Test
    void theBatchRowsLegacyConstructorHasNoStatementLimit() {
        assertNull(new AccountRepositoryCustom.AccountBalanceBatch(UUID.randomUUID(), null, null, BigDecimal.ZERO,
                BigDecimal.ZERO).latestStatementCreditLimit());
    }

    // ------------------------------------------------------------------ single-account path

    private Account owned(Account a, String totalSum) {
        when(accountRepository.findById(a.getId())).thenReturn(Optional.of(a));
        when(transactionRepository.findTotalTransactionSumByAccountId(a.getId())).thenReturn(new BigDecimal(totalSum));
        return a;
    }

    @Test
    void aCardWithItsOwnLimitNeverReadsTheStatementLimit() {
        Account card = owned(account(AccountType.credit_card, "100000"), "-30000");

        assertEquals(new BigDecimal("30.0"), service.getAccountById(card.getId()).getUtilizationPct());
        verify(statementRepository, never()).findLatestCreditLimits(any(), any());
    }

    @Test
    void aCardWithoutALimitFallsBackToTheLatestStatementLimit() {
        Account card = owned(account(AccountType.credit_card, null), "-30000");
        when(statementRepository.findLatestCreditLimits(eq(card.getId()), any())).thenReturn(List.of(new BigDecimal("50000")));

        assertEquals(new BigDecimal("60.0"), service.getAccountById(card.getId()).getUtilizationPct());
    }

    @Test
    void aCardWithNoLimitAnywhereHasNoUtilisation() {
        Account card = owned(account(AccountType.credit_card, null), "-30000");
        when(statementRepository.findLatestCreditLimits(eq(card.getId()), any())).thenReturn(List.of());

        assertNull(service.getAccountById(card.getId()).getUtilizationPct());
    }

    @Test
    void aBankAccountHasNoUtilisationAndNoLimitQuery() {
        Account bank = owned(account(AccountType.bank_account, null), "100");

        assertNull(service.getAccountById(bank.getId()).getUtilizationPct());
        verify(statementRepository, never()).findLatestCreditLimits(any(), any());
    }

    @Test
    void populateLiveBalanceFillsBalanceAndUtilisationOnTheSameEntity() {
        Account card = account(AccountType.credit_card, "100000");
        when(transactionRepository.findTotalTransactionSumByAccountId(card.getId())).thenReturn(new BigDecimal("-12345"));

        Account result = service.populateLiveBalance(card);

        assertSame(card, result);
        assertEquals(new BigDecimal("-12345"), card.getCalculatedBalance());
        assertEquals(new BigDecimal("12.3"), card.getUtilizationPct());
    }

    @Test
    void theAccountResponseCarriesTheCardsUtilisation() {
        Account card = owned(account(AccountType.credit_card, "100000"), "-30000");
        AccountResponse response = AccountResponse.from(service.getAccountById(card.getId()));

        assertEquals(new BigDecimal("30.0"),
                ((AccountResponse.CreditCardAccountResponse) response).utilizationPct());
    }

    // ------------------------------------------------------------------ cycle summary

    private void oneStatement(Account card, String statementLimit) {
        Statement s = new Statement();
        s.setId(UUID.randomUUID());
        s.setPeriodStart(LocalDate.of(2026, 9, 1));
        s.setPeriodEnd(LocalDate.of(2026, 9, 30));
        StatementCreditCardDetails d = new StatementCreditCardDetails();
        d.setTotalAmountDue(new BigDecimal("90000"));
        d.setCreditLimit(statementLimit == null ? null : new BigDecimal(statementLimit));
        s.setCreditCardDetails(d);
        when(statementRepository.findByAccountIdOrderByPeriodEndAsc(card.getId())).thenReturn(List.of(s));
    }

    @Test
    void cycleSummaryUsesTheLiveBalanceAndTheCardsOwnLimit() {
        Account card = owned(account(AccountType.credit_card, "200000"), "-20000");
        oneStatement(card, "100000");

        CardCycleSummaryResponse summary = service.getCardCycleSummary(card.getId());

        assertEquals(new BigDecimal("200000"), summary.creditLimit());
        assertEquals(new BigDecimal("10.0"), summary.utilizationPct(), "live 20,000 owed, not the statement's 90,000 due");
        verify(statementRepository, never()).findLatestCreditLimits(any(), any());
    }

    @Test
    void cycleSummaryFallsBackToTheLatestStatementLimit() {
        Account card = owned(account(AccountType.credit_card, null), "-20000");
        oneStatement(card, null);
        when(statementRepository.findLatestCreditLimits(eq(card.getId()), any())).thenReturn(List.of(new BigDecimal("80000")));

        CardCycleSummaryResponse summary = service.getCardCycleSummary(card.getId());

        assertEquals(new BigDecimal("80000"), summary.creditLimit());
        assertEquals(new BigDecimal("25.0"), summary.utilizationPct());
    }

    @Test
    void cycleSummaryOfACardInCreditIsZeroPercent() {
        Account card = owned(account(AccountType.credit_card, "100000"), "2500");
        oneStatement(card, "100000");

        assertEquals(new BigDecimal("0.0"), service.getCardCycleSummary(card.getId()).utilizationPct());
    }
}
