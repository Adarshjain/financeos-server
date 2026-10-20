package com.financeos.domain.notification.bill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.financeos.core.exception.ValidationException;
import com.financeos.core.time.AppTime;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.account.AccountService;
import com.financeos.domain.account.AccountType;
import com.financeos.domain.statement.Statement;
import com.financeos.domain.statement.StatementCreditCardDetails;
import com.financeos.domain.statement.StatementRepository;
import com.financeos.domain.transaction.TransactionRepository;
import com.financeos.domain.user.User;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** PATCH /bills/{id}/details: the merged period, due date and amounts must hang together. */
class CardBillUpdateDetailsValidationTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 20);
    private static final LocalDate END = LocalDate.of(2026, 9, 30);

    private StatementRepository statementRepository;
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
        when(accountService.populateLiveBalance(any())).thenAnswer(inv -> inv.getArgument(0));
        when(statementRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        service = new CardBillService(accountRepository, statementRepository, transactionRepository, accountService);
        user = new User();
        user.setId(UUID.randomUUID());
        card = new Account();
        card.setId(UUID.randomUUID());
        card.setUser(user);
        card.setName("HDFC Regalia");
        card.setType(AccountType.credit_card);
    }

    @AfterEach
    void tearDown() {
        AppTime.reset();
    }

    private Statement statement(LocalDate start, LocalDate end, LocalDate due, BigDecimal total, BigDecimal min) {
        Statement s = new Statement();
        s.setId(UUID.randomUUID());
        s.setUser(user);
        s.setAccount(card);
        s.setStatementType("credit_card");
        s.setPeriodStart(start);
        s.setPeriodEnd(end);
        s.setCreatedAt(Instant.parse("2026-10-01T00:00:00Z"));
        StatementCreditCardDetails d = new StatementCreditCardDetails(s);
        d.setUser(user);
        d.setPaymentDueDate(due);
        d.setTotalAmountDue(total);
        d.setMinimumAmountDue(min);
        s.setCreditCardDetails(d);
        when(statementRepository.findById(s.getId())).thenReturn(Optional.of(s));
        return s;
    }

    private Statement complete() {
        return statement(END.minusDays(29), END, END.plusDays(18), new BigDecimal("1000"), new BigDecimal("50"));
    }

    private String rejected(Statement s, LocalDate start, LocalDate end, LocalDate due, String total, String min) {
        return assertThrows(ValidationException.class, () -> service.updateDetails(user.getId(), s.getId(), start, end,
                due, total == null ? null : new BigDecimal(total), min == null ? null : new BigDecimal(min))).getMessage();
    }

    @Test
    void fillsPeriodStartAndEndOnAStatementThatLackedThem() {
        Statement s = statement(null, null, null, null, null);

        CardBill bill = service.updateDetails(user.getId(), s.getId(), END.minusDays(29), END, END.plusDays(20),
                new BigDecimal("900"), new BigDecimal("45"));

        assertEquals(END, s.getPeriodEnd());
        assertEquals(END.minusDays(29), s.getPeriodStart());
        assertEquals(new BigDecimal("900"), s.getClosingBalance());
        assertTrue(bill.missingDetails().isEmpty());
    }

    @Test
    void anOldStatementIsValid() {
        LocalDate end = TODAY.minusYears(5);
        Statement s = statement(null, null, null, null, null);

        service.updateDetails(user.getId(), s.getId(), end.minusDays(29), end, end.plusDays(18), new BigDecimal("10"), new BigDecimal("1"));

        assertEquals(end, s.getPeriodEnd());
    }

    @Test
    void periodStartAfterEndIsRejected() {
        Statement s = complete();
        assertEquals("Period start must be on or before period end", rejected(s, END.plusDays(1), null, null, null, null));
        assertEquals("Period start must be on or before period end", rejected(s, null, END.minusDays(40), null, null, null));
        service.updateDetails(user.getId(), s.getId(), END, null, null, null, null); // equal is fine
    }

    @Test
    void aPeriodLongerThan400DaysIsRejectedButExactly400IsFine() {
        Statement s = complete();
        assertEquals("Statement period cannot be longer than 400 days", rejected(s, END.minusDays(401), null, null, null, null));
        service.updateDetails(user.getId(), s.getId(), END.minusDays(400), null, null, null, null);
    }

    @Test
    void theDueDateMustFallZeroToSixtyDaysAfterThePeriodEnd() {
        Statement s = complete();
        String msg = "Due date must be 0 to 60 days after the period end";
        assertEquals(msg, rejected(s, null, null, END.minusDays(1), null, null));
        assertEquals(msg, rejected(s, null, null, END.plusDays(61), null, null));
        service.updateDetails(user.getId(), s.getId(), null, null, END, null, null);
        service.updateDetails(user.getId(), s.getId(), null, null, END.plusDays(60), null, null);
    }

    @Test
    void movingThePeriodEndChecksTheStoredDueDate() {
        Statement s = complete(); // due = END + 18
        assertEquals("Due date must be 0 to 60 days after the period end",
                rejected(s, null, END.plusDays(25), null, null, null));
        // Moving the end and the due date together is fine.
        service.updateDetails(user.getId(), s.getId(), null, END.plusDays(20), END.plusDays(30), null, null);
        assertEquals(END.plusDays(20), s.getPeriodEnd());
    }

    @Test
    void minimumMayNotExceedTheTotalWhicheverSideIsEdited() {
        Statement s = complete(); // total 1000, min 50
        String msg = "Minimum due cannot exceed total due";
        assertEquals(msg, rejected(s, null, null, null, null, "1001"));
        assertEquals(msg, rejected(s, null, null, null, "49", null));
        assertEquals(msg, rejected(s, null, null, null, "10", "20"));
        service.updateDetails(user.getId(), s.getId(), null, null, null, new BigDecimal("50"), null); // equal is fine
    }

    @Test
    void negativeAmountsAreRejectedAndANothingRequestToo() {
        Statement s = complete();
        assertEquals("Total due cannot be negative", rejected(s, null, null, null, "-1", null));
        assertEquals("Minimum due cannot be negative", rejected(s, null, null, null, null, "-1"));
        assertThrows(ValidationException.class, () -> service.updateDetails(user.getId(), s.getId(), null, null, null, null, null));
    }

    @Test
    void anUnrelatedEditIsNotBlockedByAnOldOddity() {
        Statement s = statement(END.minusDays(29), END, END.plusDays(90), new BigDecimal("1000"), new BigDecimal("50"));
        service.updateDetails(user.getId(), s.getId(), null, null, null, null, new BigDecimal("60"));
        assertEquals(new BigDecimal("60"), s.getCreditCardDetails().getMinimumAmountDue());
    }

    @Test
    void aValidationFailureChangesNothing() {
        Statement s = complete();
        rejected(s, END.minusDays(10), END.minusDays(20), null, null, null);
        assertEquals(END.minusDays(29), s.getPeriodStart());
        assertEquals(END, s.getPeriodEnd());
    }
}
