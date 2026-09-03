package com.financeos.domain.account;

import com.financeos.core.security.UserContext;
import com.financeos.domain.account.card.CardholderRepository;
import com.financeos.domain.holding.HoldingValuationService;
import com.financeos.domain.statement.StatementRepository;
import com.financeos.domain.transaction.TransactionRepository;
import com.financeos.domain.user.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccountBalanceEquivalenceTest {

    @Mock
    private AccountRepository accountRepository;
    @Mock
    private StatementRepository statementRepository;
    @Mock
    private TransactionRepository transactionRepository;
    @Mock
    private CardholderRepository cardholderRepository;
    @Mock
    private HoldingValuationService holdingValuationService;

    @InjectMocks
    private AccountService accountService;

    private User testUser;

    @BeforeEach
    void setUp() {
        testUser = new User();
        testUser.setId(UUID.randomUUID());
        UserContext.setCurrentUserId(testUser.getId());
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    @DisplayName("F3 Equivalence: Anchored Bank Account with Gap produces identical balances across getAllAccounts and getAccountById")
    void testAnchoredBankAccountWithGapEquivalence() {
        UUID accountId = UUID.randomUUID();
        LocalDate anchorDate = LocalDate.of(2026, 7, 31);
        BigDecimal openingBalance = new BigDecimal("10000.00");
        BigDecimal anchorClosingBalance = new BigDecimal("12500.00");
        BigDecimal totalSum = new BigDecimal("3000.00");
        BigDecimal postAnchorSum = new BigDecimal("500.00");

        // 1. Setup single account fetch mocks
        Account singleAccount = new Account("HDFC Salary", AccountType.bank_account);
        singleAccount.setId(accountId);
        singleAccount.setUser(testUser);
        AccountBankDetails bankDetails = new AccountBankDetails(singleAccount, openingBalance, "1234", null);
        singleAccount.setBankDetails(bankDetails);

        StatementRepository.AnchorStatementProjection anchorProj = new StatementRepository.AnchorStatementProjection() {
            @Override
            public UUID getId() { return UUID.randomUUID(); }
            @Override
            public LocalDate getPeriodEnd() { return anchorDate; }
            @Override
            public BigDecimal getClosingBalance() { return anchorClosingBalance; }
        };

        TransactionRepository.BalanceAggregatesProjection aggProj = new TransactionRepository.BalanceAggregatesProjection() {
            @Override
            public BigDecimal getTotalSum() { return totalSum; }
            @Override
            public BigDecimal getPostAnchorSum() { return postAnchorSum; }
        };

        when(accountRepository.findById(accountId)).thenReturn(Optional.of(singleAccount));
        when(statementRepository.findEligibleAnchorStatements(eq(accountId), any(PageRequest.class)))
                .thenReturn(List.of(anchorProj));
        when(transactionRepository.findBalanceAggregatesByAccountId(accountId, anchorDate))
                .thenReturn(aggProj);

        Account singleResult = accountService.getAccountById(accountId);

        // 2. Setup batch fetch mocks
        Account batchAccount = new Account("HDFC Salary", AccountType.bank_account);
        batchAccount.setId(accountId);
        batchAccount.setUser(testUser);
        AccountBankDetails batchBankDetails = new AccountBankDetails(batchAccount, openingBalance, "1234", null);
        batchAccount.setBankDetails(batchBankDetails);

        when(accountRepository.findAll()).thenReturn(List.of(batchAccount));
        when(cardholderRepository.findByAccountIdInWithCards(any())).thenReturn(List.of());
        when(accountRepository.findAccountBalanceBatches(List.of(accountId))).thenReturn(Map.of(
                accountId,
                new AccountRepositoryCustom.AccountBalanceBatch(
                        accountId,
                        anchorDate,
                        anchorClosingBalance,
                        totalSum,
                        postAnchorSum
                )
        ));

        List<Account> batchResult = accountService.getAllAccounts();
        Account batchItem = batchResult.get(0);

        // 3. Assert exact equivalence
        assertThat(batchItem.getCalculatedBalance()).isEqualByComparingTo(singleResult.getCalculatedBalance());
        assertThat(batchItem.getBalanceAnchored()).isEqualTo(singleResult.getBalanceAnchored());
        assertThat(batchItem.getAnchorDate()).isEqualTo(singleResult.getAnchorDate());
        assertThat(batchItem.getReconciliationGap()).isEqualTo(singleResult.getReconciliationGap());

        // Also check exact expected values
        // Anchored balance = 12500.00 + 500.00 = 13000.00
        assertThat(singleResult.getCalculatedBalance()).isEqualByComparingTo(new BigDecimal("13000.00"));
        assertThat(singleResult.getBalanceAnchored()).isTrue();
        assertThat(singleResult.getAnchorDate()).isEqualTo(anchorDate);
        // Pure tx balance = 10000.00 + 3000.00 = 13000.00 -> gap = 0 (so null)
        assertThat(singleResult.getReconciliationGap()).isNull();
    }

    @Test
    @DisplayName("F3 Equivalence: Anchored Credit Card Account produces identical balances across getAllAccounts and getAccountById")
    void testAnchoredCreditCardEquivalence() {
        UUID accountId = UUID.randomUUID();
        LocalDate anchorDate = LocalDate.of(2026, 7, 15);
        BigDecimal anchorClosingBalance = new BigDecimal("4500.00"); // positive due amount -> -4500.00 liability
        BigDecimal totalSum = new BigDecimal("-6000.00");
        BigDecimal postAnchorSum = new BigDecimal("-1200.00");

        Account singleAccount = new Account("Infinia", AccountType.credit_card);
        singleAccount.setId(accountId);
        singleAccount.setUser(testUser);

        StatementRepository.AnchorStatementProjection anchorProj = new StatementRepository.AnchorStatementProjection() {
            @Override
            public UUID getId() { return UUID.randomUUID(); }
            @Override
            public LocalDate getPeriodEnd() { return anchorDate; }
            @Override
            public BigDecimal getClosingBalance() { return anchorClosingBalance; }
        };

        TransactionRepository.BalanceAggregatesProjection aggProj = new TransactionRepository.BalanceAggregatesProjection() {
            @Override
            public BigDecimal getTotalSum() { return totalSum; }
            @Override
            public BigDecimal getPostAnchorSum() { return postAnchorSum; }
        };

        when(accountRepository.findById(accountId)).thenReturn(Optional.of(singleAccount));
        when(statementRepository.findEligibleAnchorStatements(eq(accountId), any(PageRequest.class)))
                .thenReturn(List.of(anchorProj));
        when(transactionRepository.findBalanceAggregatesByAccountId(accountId, anchorDate))
                .thenReturn(aggProj);

        Account singleResult = accountService.getAccountById(accountId);

        Account batchAccount = new Account("Infinia", AccountType.credit_card);
        batchAccount.setId(accountId);
        batchAccount.setUser(testUser);

        when(accountRepository.findAll()).thenReturn(List.of(batchAccount));
        when(cardholderRepository.findByAccountIdInWithCards(any())).thenReturn(List.of());
        when(accountRepository.findAccountBalanceBatches(List.of(accountId))).thenReturn(Map.of(
                accountId,
                new AccountRepositoryCustom.AccountBalanceBatch(
                        accountId,
                        anchorDate,
                        anchorClosingBalance,
                        totalSum,
                        postAnchorSum
                )
        ));

        List<Account> batchResult = accountService.getAllAccounts();
        Account batchItem = batchResult.get(0);

        assertThat(batchItem.getCalculatedBalance()).isEqualByComparingTo(singleResult.getCalculatedBalance());
        assertThat(batchItem.getBalanceAnchored()).isEqualTo(singleResult.getBalanceAnchored());
        assertThat(batchItem.getAnchorDate()).isEqualTo(singleResult.getAnchorDate());
        assertThat(batchItem.getReconciliationGap()).isEqualTo(singleResult.getReconciliationGap());

        // Expected anchored balance: -4500.00 + (-1200.00) = -5700.00
        assertThat(singleResult.getCalculatedBalance()).isEqualByComparingTo(new BigDecimal("-5700.00"));
        assertThat(singleResult.getBalanceAnchored()).isTrue();
        assertThat(singleResult.getReconciliationGap()).isNull();
    }

    @Test
    @DisplayName("F3 Equivalence: Non-anchored Bank Account with transaction sum produces identical balances")
    void testNonAnchoredBankAccountEquivalence() {
        UUID accountId = UUID.randomUUID();
        BigDecimal openingBalance = new BigDecimal("5000.00");
        BigDecimal totalSum = new BigDecimal("1200.50");

        Account singleAccount = new Account("SBI Savings", AccountType.bank_account);
        singleAccount.setId(accountId);
        singleAccount.setUser(testUser);
        AccountBankDetails bankDetails = new AccountBankDetails(singleAccount, openingBalance, "5678", null);
        singleAccount.setBankDetails(bankDetails);

        when(accountRepository.findById(accountId)).thenReturn(Optional.of(singleAccount));
        when(statementRepository.findEligibleAnchorStatements(eq(accountId), any(PageRequest.class)))
                .thenReturn(List.of());
        when(transactionRepository.findTotalTransactionSumByAccountId(accountId)).thenReturn(totalSum);

        Account singleResult = accountService.getAccountById(accountId);

        Account batchAccount = new Account("SBI Savings", AccountType.bank_account);
        batchAccount.setId(accountId);
        batchAccount.setUser(testUser);
        AccountBankDetails batchBankDetails = new AccountBankDetails(batchAccount, openingBalance, "5678", null);
        batchAccount.setBankDetails(batchBankDetails);

        when(accountRepository.findAll()).thenReturn(List.of(batchAccount));
        when(cardholderRepository.findByAccountIdInWithCards(any())).thenReturn(List.of());
        when(accountRepository.findAccountBalanceBatches(List.of(accountId))).thenReturn(Map.of(
                accountId,
                new AccountRepositoryCustom.AccountBalanceBatch(
                        accountId,
                        null,
                        null,
                        totalSum,
                        null
                )
        ));

        List<Account> batchResult = accountService.getAllAccounts();
        Account batchItem = batchResult.get(0);

        assertThat(batchItem.getCalculatedBalance()).isEqualByComparingTo(singleResult.getCalculatedBalance());
        assertThat(batchItem.getBalanceAnchored()).isEqualTo(singleResult.getBalanceAnchored());
        assertThat(batchItem.getAnchorDate()).isEqualTo(singleResult.getAnchorDate());
        assertThat(batchItem.getReconciliationGap()).isEqualTo(singleResult.getReconciliationGap());

        assertThat(singleResult.getCalculatedBalance()).isEqualByComparingTo(new BigDecimal("6200.50"));
        assertThat(singleResult.getBalanceAnchored()).isFalse();
    }
}
