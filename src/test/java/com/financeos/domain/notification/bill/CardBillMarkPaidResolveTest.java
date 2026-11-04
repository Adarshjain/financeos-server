package com.financeos.domain.notification.bill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.financeos.core.time.AppTime;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountCreditCardDetails;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.account.AccountService;
import com.financeos.domain.account.AccountType;
import com.financeos.domain.notification.reminder.ReminderKeys;
import com.financeos.domain.notification.reminder.ReminderResolveEvent;
import com.financeos.domain.statement.Statement;
import com.financeos.domain.statement.StatementCreditCardDetails;
import com.financeos.domain.statement.StatementRepository;
import com.financeos.domain.transaction.TransactionRepository;
import com.financeos.domain.user.User;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

/** Marking a bill paid in full resolves its feed reminder at once (after commit); a partial mark does not. */
class CardBillMarkPaidResolveTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 20);

    private final AccountRepository accountRepository = mock(AccountRepository.class);
    private final StatementRepository statementRepository = mock(StatementRepository.class);
    private final TransactionRepository transactionRepository = mock(TransactionRepository.class);
    private final AccountService accountService = mock(AccountService.class);
    private final ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
    private final CardBillService service = new CardBillService(accountRepository, statementRepository, transactionRepository, accountService);
    private User user;
    private Statement statement;

    @BeforeEach
    void setUp() {
        AppTime.useClock(Clock.fixed(TODAY.atTime(10, 0).atZone(IST).toInstant(), IST));
        service.setEventPublisher(publisher);
        when(accountService.populateLiveBalance(any())).thenAnswer(inv -> inv.getArgument(0));
        BatchRepositoryBridge.install(statementRepository, transactionRepository, accountService);
        when(transactionRepository.sumCreditsAfter(any(), any())).thenReturn(BigDecimal.ZERO);
        when(statementRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        user = new User();
        user.setId(UUID.randomUUID());
        Account card = new Account();
        card.setId(UUID.randomUUID());
        card.setUser(user);
        card.setName("HDFC Regalia");
        card.setType(AccountType.credit_card);
        card.setCreditCardDetails(new AccountCreditCardDetails(card, new BigDecimal("200000"), null));
        statement = new Statement();
        statement.setId(UUID.randomUUID());
        statement.setUser(user);
        statement.setAccount(card);
        statement.setStatementType("credit_card");
        statement.setPeriodStart(TODAY.minusDays(39));
        statement.setPeriodEnd(TODAY.minusDays(10));
        statement.setCreatedAt(TODAY.minusDays(9).atStartOfDay(IST).toInstant());
        StatementCreditCardDetails d = new StatementCreditCardDetails(statement);
        d.setUser(user);
        d.setTotalAmountDue(new BigDecimal("48250"));
        d.setPaymentDueDate(TODAY.plusDays(18));
        statement.setCreditCardDetails(d);
        when(statementRepository.findById(statement.getId())).thenReturn(Optional.of(statement));
    }

    @AfterEach
    void tearDown() {
        AppTime.reset();
    }

    @Test
    void payingInFullPublishesTheResolveEventForThatBill() {
        service.markPaid(user.getId(), statement.getId(), null, null);

        ArgumentCaptor<ReminderResolveEvent> event = ArgumentCaptor.forClass(ReminderResolveEvent.class);
        verify(publisher).publishEvent(event.capture());
        assertEquals(new ReminderResolveEvent(user.getId(), ReminderKeys.bill(statement.getId())), event.getValue());
    }

    @Test
    void aPartialPaymentKeepsTheReminderAlive() {
        service.markPaid(user.getId(), statement.getId(), new BigDecimal("5000"), TODAY.minusDays(1));

        verify(publisher, never()).publishEvent(any(Object.class));
    }
}
