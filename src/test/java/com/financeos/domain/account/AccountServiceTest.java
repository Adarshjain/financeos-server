package com.financeos.domain.account;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.financeos.api.account.dto.CardCycleSummaryResponse;
import com.financeos.api.account.dto.CreateAccountRequest;
import com.financeos.core.exception.ResourceNotFoundException;
import com.financeos.core.exception.ValidationException;
import com.financeos.core.security.UserContext;
import com.financeos.domain.holding.HoldingValuationService;
import com.financeos.domain.statement.Statement;
import com.financeos.domain.statement.StatementCreditCardDetails;
import com.financeos.domain.statement.StatementRepository;
import com.financeos.domain.statement.StatementVerdict;
import com.financeos.domain.transaction.TransactionRepository;
import com.financeos.domain.user.User;
import com.financeos.domain.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

class AccountServiceTest {

    private AccountRepository accountRepository;
    private UserRepository userRepository;
    private StatementRepository statementRepository;
    private TransactionRepository transactionRepository;
    private com.financeos.gmail.domain.GmailBackfillDemandRepository backfillDemandRepository;
    private org.springframework.context.ApplicationEventPublisher eventPublisher;
    private AccountService accountService;

    @BeforeEach
    void setUp() {
        accountRepository = mock(AccountRepository.class);
        userRepository = mock(UserRepository.class);
        statementRepository = mock(StatementRepository.class);
        transactionRepository = mock(TransactionRepository.class);
        HoldingValuationService holdingValuationService = mock(HoldingValuationService.class);
        backfillDemandRepository = mock(com.financeos.gmail.domain.GmailBackfillDemandRepository.class);
        eventPublisher = mock(org.springframework.context.ApplicationEventPublisher.class);
        accountService = new AccountService(accountRepository, userRepository, statementRepository, transactionRepository, holdingValuationService, backfillDemandRepository, eventPublisher);
        UserContext.clear();
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void getAccountById_sameUser_success() {
        UUID userId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        UserContext.setCurrentUserId(userId);

        User user = new User();
        user.setId(userId);

        Account account = new Account();
        account.setId(accountId);
        account.setUser(user);

        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));

        Account result = accountService.getAccountById(accountId);
        assertNotNull(result);
        assertEquals(accountId, result.getId());
    }

    @Test
    void getAccountById_differentUser_throwsValidationException() {
        UUID userId = UUID.randomUUID();
        UUID otherUserId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        UserContext.setCurrentUserId(userId);

        User otherUser = new User();
        otherUser.setId(otherUserId);

        Account account = new Account();
        account.setId(accountId);
        account.setUser(otherUser);

        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));

        assertThrows(ValidationException.class, () -> accountService.getAccountById(accountId));
    }

    @Test
    void deleteAccount_sameUser_success() {
        UUID userId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        UserContext.setCurrentUserId(userId);

        User user = new User();
        user.setId(userId);

        Account account = new Account();
        account.setId(accountId);
        account.setUser(user);

        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));

        assertDoesNotThrow(() -> accountService.deleteAccount(accountId));
        verify(accountRepository, times(1)).delete(account);
    }

    @Test
    void deleteAccount_differentUser_throwsValidationException() {
        UUID userId = UUID.randomUUID();
        UUID otherUserId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        UserContext.setCurrentUserId(userId);

        User otherUser = new User();
        otherUser.setId(otherUserId);

        Account account = new Account();
        account.setId(accountId);
        account.setUser(otherUser);

        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));

        assertThrows(ValidationException.class, () -> accountService.deleteAccount(accountId));
        verify(accountRepository, never()).delete(any());
    }

    @Test
    void getAccountById_creditCardAnchoredBalance_positiveOwedBase() {
        UUID userId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        UserContext.setCurrentUserId(userId);

        User user = new User();
        user.setId(userId);

        Account account = new Account("CC", AccountType.credit_card);
        account.setId(accountId);
        account.setUser(user);

        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));

        StatementRepository.AnchorStatementProjection proj = mock(StatementRepository.AnchorStatementProjection.class);
        when(proj.getClosingBalance()).thenReturn(new BigDecimal("5000.00"));
        LocalDate anchorDate = LocalDate.of(2026, 6, 30);
        when(proj.getPeriodEnd()).thenReturn(anchorDate);

        when(statementRepository.findEligibleAnchorStatements(eq(accountId), any())).thenReturn(List.of(proj));

        TransactionRepository.BalanceAggregatesProjection agg = mock(TransactionRepository.BalanceAggregatesProjection.class);
        when(agg.getPostAnchorSum()).thenReturn(new BigDecimal("2000.00")); // -1000 purchase + 3000 payment = +2000
        when(transactionRepository.findBalanceAggregatesByAccountId(accountId, anchorDate)).thenReturn(agg);

        Account res = accountService.getAccountById(accountId);

        assertNotNull(res.getCalculatedBalance());
        assertEquals(new BigDecimal("-3000.00"), res.getCalculatedBalance()); // base -5000 + 2000 = -3000
        assertTrue(res.getBalanceAnchored());
        assertEquals(anchorDate, res.getAnchorDate());
    }

    @Test
    void getAccountById_creditCardAnchoredBalance_negativeCreditBase() {
        UUID userId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        UserContext.setCurrentUserId(userId);

        User user = new User();
        user.setId(userId);

        Account account = new Account("CC", AccountType.credit_card);
        account.setId(accountId);
        account.setUser(user);

        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));

        StatementRepository.AnchorStatementProjection proj = mock(StatementRepository.AnchorStatementProjection.class);
        when(proj.getClosingBalance()).thenReturn(new BigDecimal("-200.00"));
        LocalDate anchorDate = LocalDate.of(2026, 6, 30);
        when(proj.getPeriodEnd()).thenReturn(anchorDate);

        when(statementRepository.findEligibleAnchorStatements(eq(accountId), any())).thenReturn(List.of(proj));

        TransactionRepository.BalanceAggregatesProjection agg = mock(TransactionRepository.BalanceAggregatesProjection.class);
        when(agg.getPostAnchorSum()).thenReturn(new BigDecimal("-50.00"));
        when(transactionRepository.findBalanceAggregatesByAccountId(accountId, anchorDate)).thenReturn(agg);

        Account res = accountService.getAccountById(accountId);

        assertNotNull(res.getCalculatedBalance());
        assertEquals(new BigDecimal("150.00"), res.getCalculatedBalance()); // base +200 - 50 = +150
        assertTrue(res.getBalanceAnchored());
    }

    @Test
    void getCardCycleSummary_success() {
        UUID userId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        UserContext.setCurrentUserId(userId);

        User user = new User();
        user.setId(userId);

        Account account = new Account("CC", AccountType.credit_card);
        account.setId(accountId);
        account.setUser(user);

        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));

        Statement stmt = new Statement();
        stmt.setId(UUID.randomUUID());
        stmt.setStatementType("credit_card");
        stmt.setPeriodStart(LocalDate.of(2026, 6, 1));
        stmt.setPeriodEnd(LocalDate.of(2026, 6, 30));
        stmt.setVerdict(StatementVerdict.AUTO_INGEST);

        StatementCreditCardDetails details = new StatementCreditCardDetails();
        details.setTotalAmountDue(new BigDecimal("15000.00"));
        details.setMinimumAmountDue(new BigDecimal("1500.00"));
        details.setCreditLimit(new BigDecimal("100000.00"));
        details.setPaymentDueDate(LocalDate.now().plusDays(10));
        stmt.setCreditCardDetails(details);

        when(statementRepository.findQualifyingCreditCardStatements(accountId)).thenReturn(List.of(stmt));

        CardCycleSummaryResponse summary = accountService.getCardCycleSummary(accountId);

        assertNotNull(summary);
        assertEquals(stmt.getId(), summary.statementId());
        assertEquals(new BigDecimal("15.00"), summary.utilizationPct());
        assertEquals(10L, summary.daysUntilDue());
        assertEquals(1, summary.history().size());
    }

    @Test
    void testRatchetMatrix_NewlySet_CreatesDemandRow() {
        UUID userId = UUID.randomUUID();
        UserContext.setCurrentUserId(userId);
        User user = new User();
        user.setId(userId);
        when(userRepository.getReferenceById(userId)).thenReturn(user);

        when(accountRepository.save(any())).thenAnswer(inv -> {
            Account a = inv.getArgument(0);
            a.setId(UUID.randomUUID());
            return a;
        });
        when(backfillDemandRepository.findById(userId)).thenReturn(Optional.empty());

        CreateAccountRequest req = new CreateAccountRequest.GenericAccountRequest(
                "Cash", AccountType.generic, false, FinancialPosition.asset, "desc", LocalDate.of(2026, 1, 1)
        );

        Account saved = accountService.createAccount(req);

        assertNotNull(saved);
        verify(backfillDemandRepository).save(argThat(d -> d.getFloorDate().equals(LocalDate.of(2026, 1, 1))));
    }

    @Test
    void testRatchetMatrix_DateLowered_LowersFloor() {
        UUID userId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        UserContext.setCurrentUserId(userId);
        User user = new User();
        user.setId(userId);

        Account account = new Account("Bank", AccountType.bank_account);
        account.setId(accountId);
        account.setUser(user);
        account.setIngestFromDate(LocalDate.of(2026, 6, 1));

        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));
        when(accountRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        com.financeos.gmail.domain.GmailBackfillDemand demand = new com.financeos.gmail.domain.GmailBackfillDemand(user, LocalDate.of(2026, 6, 1));
        when(backfillDemandRepository.findById(userId)).thenReturn(Optional.of(demand));

        CreateAccountRequest req = new CreateAccountRequest.BankAccountRequest(
                "Bank", AccountType.bank_account, false, FinancialPosition.asset, "desc",
                new BigDecimal("100"), "1234", null, LocalDate.of(2026, 1, 1)
        );

        accountService.updateAccount(accountId, req);

        verify(backfillDemandRepository).save(argThat(d -> d.getFloorDate().equals(LocalDate.of(2026, 1, 1))));
    }

    @Test
    void testRatchetMatrix_DateRaised_FloorUnchanged() {
        UUID userId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        UserContext.setCurrentUserId(userId);
        User user = new User();
        user.setId(userId);

        Account account = new Account("Bank", AccountType.bank_account);
        account.setId(accountId);
        account.setUser(user);
        account.setIngestFromDate(LocalDate.of(2026, 1, 1));

        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));
        when(accountRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CreateAccountRequest req = new CreateAccountRequest.BankAccountRequest(
                "Bank", AccountType.bank_account, false, FinancialPosition.asset, "desc",
                new BigDecimal("100"), "1234", null, LocalDate.of(2026, 6, 1)
        );

        accountService.updateAccount(accountId, req);

        verify(backfillDemandRepository, never()).save(any());
    }

    @Test
    void testRatchetMatrix_UnrelatedEdit_NoDemandWrite() {
        UUID userId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        UserContext.setCurrentUserId(userId);
        User user = new User();
        user.setId(userId);

        Account account = new Account("Bank", AccountType.bank_account);
        account.setId(accountId);
        account.setUser(user);
        account.setIngestFromDate(LocalDate.of(2026, 1, 1));

        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));
        when(accountRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CreateAccountRequest req = new CreateAccountRequest.BankAccountRequest(
                "Bank New Name", AccountType.bank_account, false, FinancialPosition.asset, "desc",
                new BigDecimal("100"), "1234", null, LocalDate.of(2026, 1, 1)
        );

        accountService.updateAccount(accountId, req);

        verify(backfillDemandRepository, never()).save(any());
    }

    @Test
    void testRatchetMatrix_DateCleared_NoDemandWrite() {
        UUID userId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        UserContext.setCurrentUserId(userId);
        User user = new User();
        user.setId(userId);

        Account account = new Account("Bank", AccountType.bank_account);
        account.setId(accountId);
        account.setUser(user);
        account.setIngestFromDate(LocalDate.of(2026, 1, 1));

        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));
        when(accountRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CreateAccountRequest req = new CreateAccountRequest.BankAccountRequest(
                "Bank", AccountType.bank_account, false, FinancialPosition.asset, "desc",
                new BigDecimal("100"), "1234", null, null
        );

        accountService.updateAccount(accountId, req);

        verify(backfillDemandRepository, never()).save(any());
    }
}
