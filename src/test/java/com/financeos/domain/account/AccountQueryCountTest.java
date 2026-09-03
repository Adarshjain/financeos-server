package com.financeos.domain.account;

import com.financeos.domain.account.card.Cardholder;
import com.financeos.domain.account.card.CardholderRepository;
import com.financeos.domain.account.card.CardRepository;
import com.financeos.domain.holding.HoldingValuationService;
import com.financeos.domain.statement.StatementRepository;
import com.financeos.domain.transaction.TransactionRepository;
import com.financeos.domain.user.User;
import com.financeos.domain.user.UserRepository;
import com.financeos.gmail.domain.GmailBackfillDemandRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

class AccountQueryCountTest {

    private AccountRepository accountRepository;
    private CardholderRepository cardholderRepository;
    private CardRepository cardRepository;
    private UserRepository userRepository;
    private StatementRepository statementRepository;
    private TransactionRepository transactionRepository;
    private HoldingValuationService holdingValuationService;
    private GmailBackfillDemandRepository backfillDemandRepository;
    private ApplicationEventPublisher eventPublisher;
    private AccountService accountService;

    @BeforeEach
    void setUp() {
        accountRepository = mock(AccountRepository.class);
        cardholderRepository = mock(CardholderRepository.class);
        cardRepository = mock(CardRepository.class);
        userRepository = mock(UserRepository.class);
        statementRepository = mock(StatementRepository.class);
        transactionRepository = mock(TransactionRepository.class);
        holdingValuationService = mock(HoldingValuationService.class);
        backfillDemandRepository = mock(GmailBackfillDemandRepository.class);
        eventPublisher = mock(ApplicationEventPublisher.class);

        accountService = new AccountService(
                accountRepository,
                cardholderRepository,
                cardRepository,
                userRepository,
                statementRepository,
                transactionRepository,
                holdingValuationService,
                backfillDemandRepository,
                eventPublisher
        );
    }

    @Test
    void getAllAccounts_queryCountIsConstantForNAccounts() {
        int n = 10;
        List<Account> accounts = new ArrayList<>();
        User user = new User();
        user.setId(UUID.randomUUID());

        for (int i = 0; i < n; i++) {
            Account acc = new Account("Account " + i, i % 2 == 0 ? AccountType.bank_account : AccountType.credit_card);
            acc.setId(UUID.randomUUID());
            acc.setUser(user);
            accounts.add(acc);
        }

        when(accountRepository.findAll()).thenReturn(accounts);
        when(cardholderRepository.findByAccountIdInWithCards(anyList())).thenReturn(List.of());

        java.util.Map<UUID, AccountRepositoryCustom.AccountBalanceBatch> batchMap = new java.util.HashMap<>();
        for (Account acc : accounts) {
            batchMap.put(acc.getId(), new AccountRepositoryCustom.AccountBalanceBatch(
                    acc.getId(),
                    LocalDate.of(2026, 6, 30),
                    new BigDecimal("1000.00"),
                    new BigDecimal("1200.00"),
                    new BigDecimal("200.00")
            ));
        }
        when(accountRepository.findAccountBalanceBatches(anyList())).thenReturn(batchMap);

        List<Account> result = accountService.getAllAccounts();

        assertEquals(n, result.size());

        // Verify exactly 1 call to accountRepository.findAll()
        verify(accountRepository, times(1)).findAll();

        // Verify exactly 1 call to cardholderRepository.findByAccountIdInWithCards()
        verify(cardholderRepository, times(1)).findByAccountIdInWithCards(anyList());

        // Verify exactly 1 call to accountRepository.findAccountBalanceBatches()
        verify(accountRepository, times(1)).findAccountBalanceBatches(anyList());

        // Total batch queries = 3 (constant O(1) <= 3, NOT O(N))
        verifyNoMoreInteractions(accountRepository);
        verifyNoMoreInteractions(cardholderRepository);
        verifyNoInteractions(statementRepository);
        verifyNoInteractions(transactionRepository);
    }
}
