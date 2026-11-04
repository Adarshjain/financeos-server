package com.financeos.domain.notification.bill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.financeos.core.time.AppTime;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountCreditCardDetails;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.account.AccountService;
import com.financeos.domain.account.AccountType;
import com.financeos.domain.statement.Statement;
import com.financeos.domain.statement.StatementCreditCardDetails;
import com.financeos.domain.statement.StatementRepository;
import com.financeos.domain.statement.skip.StatementSkippedPeriodRepository;
import com.financeos.domain.transaction.TransactionRepository;
import com.financeos.domain.user.User;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** {@code nextStatementExpectedOn} advances past cycles marked "No statement this period". */
class CardBillSkippedPeriodTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 20);

    private StatementRepository statementRepository;
    private StatementSkippedPeriodRepository skipRepository;
    private CardBillService service;
    private User user;
    private Account card;

    @BeforeEach
    void setUp() {
        AppTime.useClock(Clock.fixed(TODAY.atTime(10, 0).atZone(IST).toInstant(), IST));
        AccountRepository accountRepository = mock(AccountRepository.class);
        statementRepository = mock(StatementRepository.class);
        TransactionRepository transactionRepository = mock(TransactionRepository.class);
        AccountService accountService = mock(AccountService.class);
        skipRepository = mock(StatementSkippedPeriodRepository.class);
        when(accountService.populateLiveBalance(any())).thenAnswer(inv -> inv.getArgument(0));
        service = new CardBillService(accountRepository, statementRepository, transactionRepository, accountService);
        service.setSkippedRepository(skipRepository);
        BatchRepositoryBridge.install(statementRepository, transactionRepository, accountService);
        user = new User();
        user.setId(UUID.randomUUID());
        card = new Account();
        card.setId(UUID.randomUUID());
        card.setUser(user);
        card.setName("HDFC Regalia");
        card.setType(AccountType.credit_card);
        card.setCreditCardDetails(new AccountCreditCardDetails(card, new BigDecimal("200000"), null));
        when(accountRepository.findByUserIdAndType(user.getId(), AccountType.credit_card)).thenReturn(List.of(card));
        when(transactionRepository.sumCreditsAfter(any(), any())).thenReturn(BigDecimal.ZERO);
        when(transactionRepository.sumDebitsAfter(any(), any())).thenReturn(BigDecimal.ZERO);
        Statement s = statement(LocalDate.of(2026, 9, 10));
        when(statementRepository.findQualifyingCreditCardStatements(card.getId())).thenReturn(List.of(s));
        when(statementRepository.findByAccountIdOrderByPeriodEndDescNullsLast(card.getId())).thenReturn(List.of(s));
    }

    @AfterEach
    void tearDown() {
        AppTime.reset();
    }

    private Statement statement(LocalDate periodEnd) {
        Statement s = new Statement();
        s.setId(UUID.randomUUID());
        s.setUser(user);
        s.setAccount(card);
        s.setStatementType("credit_card");
        s.setPeriodStart(periodEnd.minusDays(29));
        s.setPeriodEnd(periodEnd);
        s.setCreatedAt(periodEnd.plusDays(1).atStartOfDay(IST).toInstant());
        s.setTransactionCount(12);
        StatementCreditCardDetails d = new StatementCreditCardDetails(s);
        d.setUser(user);
        d.setTotalAmountDue(new BigDecimal("100"));
        d.setMinimumAmountDue(new BigDecimal("5"));
        d.setPaymentDueDate(TODAY.plusDays(5));
        d.setTotalPurchases(new BigDecimal("100"));
        s.setCreditCardDetails(d);
        return s;
    }

    private void skips(LocalDate... ends) {
        List<StatementSkippedPeriodRepository.SkippedEnd> rows = new java.util.ArrayList<>();
        for (LocalDate end : ends) {
            StatementSkippedPeriodRepository.SkippedEnd row = mock(StatementSkippedPeriodRepository.SkippedEnd.class);
            when(row.getAccountId()).thenReturn(card.getId());
            when(row.getPeriodEnd()).thenReturn(end);
            rows.add(row);
        }
        when(skipRepository.findEndsByAccountIds(any())).thenReturn(rows);
    }

    private LocalDate nextExpected() {
        return service.listBills(user.getId()).get(0).nextStatementExpectedOn();
    }

    @Test
    void withoutASkipTheCycleAfterTheLatestStatementIsExpected() {
        assertEquals(LocalDate.of(2026, 10, 10), nextExpected());
    }

    @Test
    void aSkippedCycleMovesTheExpectedStatementToTheNextCycle() {
        skips(LocalDate.of(2026, 10, 10));

        assertEquals(LocalDate.of(2026, 11, 10), nextExpected());
    }

    @Test
    void severalSkipsAdvanceFromTheLatestOne() {
        skips(LocalDate.of(2026, 10, 10), LocalDate.of(2026, 11, 10));

        assertEquals(LocalDate.of(2026, 12, 10), nextExpected());
    }

    @Test
    void aSkipBeforeTheLatestStatementIsIgnored() {
        skips(LocalDate.of(2026, 8, 10));

        assertEquals(LocalDate.of(2026, 10, 10), nextExpected());
    }
}
