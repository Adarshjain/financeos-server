package com.financeos.domain.account;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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

/**
 * A credit card's {@code effectiveCreditLimit} — the limit its utilisation divides by (own limit,
 * else the latest statement's) — on the batch list path (no per-card query), the single-account
 * path, the response, and the cycle summary (read once).
 */
class AccountEffectiveCreditLimitTest {

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

    private static AccountRepositoryCustom.AccountBalanceBatch batch(Account a, String statementLimit) {
        return new AccountRepositoryCustom.AccountBalanceBatch(a.getId(), null, null, new BigDecimal("-1000"), null,
                statementLimit == null ? null : new BigDecimal(statementLimit));
    }

    private Account owned(Account a) {
        when(accountRepository.findById(a.getId())).thenReturn(Optional.of(a));
        when(transactionRepository.findTotalTransactionSumByAccountId(a.getId())).thenReturn(new BigDecimal("-1000"));
        return a;
    }

    private static BigDecimal effective(Account account) {
        return ((AccountResponse.CreditCardAccountResponse) AccountResponse.from(account)).effectiveCreditLimit();
    }

    // ------------------------------------------------------------------ list (batch) path

    @Test
    void theListPathTakesTheLimitFromTheBatchRowWithNoPerCardQuery() {
        Account own = account(AccountType.credit_card, "200000");
        Account fallback = account(AccountType.credit_card, null);
        Account zeroOwn = account(AccountType.credit_card, "0");
        Account none = account(AccountType.credit_card, null);
        Account bank = account(AccountType.bank_account, null);
        when(accountRepository.findAll()).thenReturn(List.of(own, fallback, zeroOwn, none, bank));
        when(cardholderRepository.findByAccountIdInWithCards(anyList())).thenReturn(List.of());
        Map<UUID, AccountRepositoryCustom.AccountBalanceBatch> rows = new HashMap<>();
        rows.put(own.getId(), batch(own, "50000"));
        rows.put(fallback.getId(), batch(fallback, "80000"));
        rows.put(zeroOwn.getId(), batch(zeroOwn, "40000"));
        rows.put(none.getId(), batch(none, null));
        rows.put(bank.getId(), batch(bank, "70000"));
        when(accountRepository.findAccountBalanceBatches(anyList())).thenReturn(rows);

        service.getAllAccounts();

        assertEquals(new BigDecimal("200000"), own.getEffectiveCreditLimit(), "the card's own limit wins");
        assertEquals(new BigDecimal("80000"), fallback.getEffectiveCreditLimit(), "else the latest statement's");
        assertEquals(new BigDecimal("40000"), zeroOwn.getEffectiveCreditLimit(), "a zero own limit is no limit");
        assertNull(none.getEffectiveCreditLimit());
        assertNull(bank.getEffectiveCreditLimit(), "only cards have one");
        // Utilisation divides by the same figure.
        assertEquals(new BigDecimal("1.3"), fallback.getUtilizationPct());
        assertEquals(new BigDecimal("80000"), effective(fallback));
        verifyNoInteractions(statementRepository, transactionRepository);
    }

    @Test
    void theListPathWithNoBatchRowFallsBackToTheOwnLimitOnly() {
        Account own = account(AccountType.credit_card, "100000");
        Account none = account(AccountType.credit_card, null);
        when(accountRepository.findAll()).thenReturn(List.of(own, none));
        when(cardholderRepository.findByAccountIdInWithCards(anyList())).thenReturn(List.of());
        when(accountRepository.findAccountBalanceBatches(anyList())).thenReturn(Map.of());

        service.getAllAccounts();

        assertEquals(new BigDecimal("100000"), own.getEffectiveCreditLimit());
        assertNull(none.getEffectiveCreditLimit());
    }

    // ------------------------------------------------------------------ single-account path (get / create / update responses)

    @Test
    void getUsesTheOwnLimitWithoutReadingStatements() {
        Account card = owned(account(AccountType.credit_card, "150000"));

        assertEquals(new BigDecimal("150000"), effective(service.getAccountById(card.getId())));
        verify(statementRepository, never()).findLatestCreditLimits(any(), any());
    }

    @Test
    void getFallsBackToTheLatestStatementLimit() {
        Account card = owned(account(AccountType.credit_card, null));
        when(statementRepository.findLatestCreditLimits(eq(card.getId()), any()))
                .thenReturn(List.of(new BigDecimal("60000")));

        Account loaded = service.getAccountById(card.getId());

        assertEquals(new BigDecimal("60000"), effective(loaded));
        assertEquals(new BigDecimal("1.7"), loaded.getUtilizationPct());
    }

    @Test
    void getWithNoLimitAnywhereIsNull() {
        Account card = owned(account(AccountType.credit_card, null));
        when(statementRepository.findLatestCreditLimits(eq(card.getId()), any())).thenReturn(List.of());

        assertNull(effective(service.getAccountById(card.getId())));
    }

    @Test
    void populateLiveBalanceFillsTheLimitToo() {
        Account card = account(AccountType.credit_card, "90000");
        when(transactionRepository.findTotalTransactionSumByAccountId(card.getId())).thenReturn(BigDecimal.ZERO);

        assertEquals(new BigDecimal("90000"), service.populateLiveBalance(card).getEffectiveCreditLimit());
    }

    @Test
    void aBankAccountHasNoLimit() {
        Account bank = owned(account(AccountType.bank_account, null));

        assertNull(service.getAccountById(bank.getId()).getEffectiveCreditLimit());
        verify(statementRepository, never()).findLatestCreditLimits(any(), any());
    }

    // ------------------------------------------------------------------ cycle summary

    @Test
    void theCycleSummaryShowsTheSameLimitAndReadsTheStatementFallbackOnce() {
        Account card = owned(account(AccountType.credit_card, null));
        Statement s = new Statement();
        s.setId(UUID.randomUUID());
        s.setPeriodStart(LocalDate.of(2026, 9, 1));
        s.setPeriodEnd(LocalDate.of(2026, 9, 30));
        s.setCreditCardDetails(new StatementCreditCardDetails());
        when(statementRepository.findByAccountIdOrderByPeriodEndAsc(card.getId())).thenReturn(List.of(s));
        when(statementRepository.findLatestCreditLimits(eq(card.getId()), any()))
                .thenReturn(List.of(new BigDecimal("40000")));

        CardCycleSummaryResponse summary = service.getCardCycleSummary(card.getId());

        assertEquals(new BigDecimal("40000"), summary.creditLimit());
        assertEquals(new BigDecimal("2.5"), summary.utilizationPct());
        verify(statementRepository, times(1)).findLatestCreditLimits(eq(card.getId()), any());
    }
}
