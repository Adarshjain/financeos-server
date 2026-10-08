package com.financeos.domain.notification.bill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.financeos.core.exception.ResourceNotFoundException;
import com.financeos.core.exception.ValidationException;
import com.financeos.core.time.AppTime;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountCreditCardDetails;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.account.AccountType;
import com.financeos.domain.statement.Statement;
import com.financeos.domain.statement.StatementCreditCardDetails;
import com.financeos.domain.statement.StatementRepository;
import com.financeos.domain.transaction.Transaction;
import com.financeos.domain.transaction.TransactionRepository;
import com.financeos.domain.transaction.TransactionType;
import com.financeos.domain.transaction.link.LinkType;
import com.financeos.domain.transaction.link.TransactionLink;
import com.financeos.domain.transaction.link.TransactionLinkMember;
import com.financeos.domain.transaction.link.TransactionLinkRepository;
import com.financeos.domain.user.User;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CardBillServiceTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 20);

    private AccountRepository accountRepository;
    private StatementRepository statementRepository;
    private TransactionRepository transactionRepository;
    private TransactionLinkRepository linkRepository;
    private CardBillService service;

    private User user;
    private Account card;

    @BeforeEach
    void setUp() {
        AppTime.useClock(Clock.fixed(TODAY.atTime(10, 0).atZone(IST).toInstant(), IST));
        accountRepository = mock(AccountRepository.class);
        statementRepository = mock(StatementRepository.class);
        transactionRepository = mock(TransactionRepository.class);
        linkRepository = mock(TransactionLinkRepository.class);
        service = new CardBillService(accountRepository, statementRepository, transactionRepository, linkRepository);

        user = new User();
        user.setId(UUID.randomUUID());
        card = account("HDFC Regalia");
        when(transactionRepository.findCreditsAfter(any(), any())).thenReturn(List.of());
        when(linkRepository.findDistinctByMembers_Transaction_IdIn(anyCollection())).thenReturn(List.of());
        when(statementRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @AfterEach
    void tearDown() {
        AppTime.reset();
    }

    private Account account(String name) {
        Account a = new Account();
        a.setId(UUID.randomUUID());
        a.setUser(user);
        a.setName(name);
        a.setType(AccountType.credit_card);
        a.setCreditCardDetails(new AccountCreditCardDetails(a, new BigDecimal("200000"), null));
        return a;
    }

    private Statement statement(Account account, LocalDate periodEnd, BigDecimal total, LocalDate due) {
        Statement s = new Statement();
        s.setId(UUID.randomUUID());
        s.setUser(user);
        s.setAccount(account);
        s.setStatementType("credit_card");
        s.setPeriodStart(periodEnd == null ? null : periodEnd.minusDays(29));
        s.setPeriodEnd(periodEnd);
        s.setCreatedAt(periodEnd == null ? Instant.now() : periodEnd.plusDays(1).atStartOfDay(IST).toInstant());
        s.setTransactionCount(12);
        StatementCreditCardDetails d = new StatementCreditCardDetails(s);
        d.setUser(user);
        d.setTotalAmountDue(total);
        d.setMinimumAmountDue(total == null ? null : total.multiply(new BigDecimal("0.05")));
        d.setPaymentDueDate(due);
        d.setTotalPurchases(total);
        s.setCreditCardDetails(d);
        when(statementRepository.findById(s.getId())).thenReturn(Optional.of(s));
        return s;
    }

    private Transaction credit(Account account, LocalDate date, String amount) {
        Transaction t = new Transaction();
        t.setId(UUID.randomUUID());
        t.setUser(user);
        t.setAccount(account);
        t.setDate(date);
        t.setAmount(new BigDecimal(amount));
        t.setType(TransactionType.CREDIT);
        t.setDescription("PAYMENT RECEIVED");
        return t;
    }

    private TransactionLink link(LinkType type, Transaction counterpart, boolean counterpartIsAnchor) {
        TransactionLink link = new TransactionLink();
        link.setId(UUID.randomUUID());
        link.setType(type);
        Set<TransactionLinkMember> members = new HashSet<>();
        members.add(new TransactionLinkMember(link, counterpart, counterpartIsAnchor));
        Transaction other = credit(account("Bank"), counterpart.getDate(), counterpart.getAmount().toPlainString());
        other.setType(TransactionType.DEBIT);
        members.add(new TransactionLinkMember(link, other, !counterpartIsAnchor));
        link.setMembers(members);
        return link;
    }

    // ---------------------------------------------------------------- status matrix

    @Test
    void openBillWithNothingPaid() {
        Statement s = statement(card, TODAY.minusDays(10), new BigDecimal("48250"), TODAY.plusDays(18));
        CardBill bill = service.build(card, s, TODAY);

        assertEquals(BillStatus.OPEN, bill.status());
        assertEquals(PaidSource.NONE, bill.paidSource());
        assertEquals(0, bill.paidAmount().signum());
        assertEquals(new BigDecimal("48250"), bill.remainingAmount());
        assertEquals(18L, bill.daysUntilDue());
        assertEquals("HDFC Regalia", bill.accountName());
        assertNull(bill.last4());
        assertEquals(new BigDecimal("24.1"), bill.digest().utilizationPct());
        assertEquals(12, bill.digest().transactionCount());
        assertTrue(bill.possiblePayments().isEmpty());
    }

    @Test
    void ccPaymentLinkAfterPeriodEndPaysTheBill() {
        Statement s = statement(card, TODAY.minusDays(10), new BigDecimal("48250"), TODAY.plusDays(18));
        Transaction payment = credit(card, TODAY.minusDays(2), "48250");
        when(transactionRepository.findCreditsAfter(card.getId(), s.getPeriodEnd())).thenReturn(List.of(payment));
        when(linkRepository.findDistinctByMembers_Transaction_IdIn(anyCollection()))
                .thenReturn(List.of(link(LinkType.CC_PAYMENT, payment, false)));

        CardBill bill = service.build(card, s, TODAY);

        assertEquals(BillStatus.PAID, bill.status());
        assertEquals(PaidSource.LINK, bill.paidSource());
        assertEquals(new BigDecimal("48250"), bill.paidAmount());
        assertEquals(0, bill.remainingAmount().signum());
        assertTrue(bill.possiblePayments().isEmpty());
    }

    @Test
    void partialLinkedPaymentLeavesTheRemainder() {
        Statement s = statement(card, TODAY.minusDays(10), new BigDecimal("48250"), TODAY.plusDays(18));
        Transaction payment = credit(card, TODAY.minusDays(2), "20000");
        when(transactionRepository.findCreditsAfter(card.getId(), s.getPeriodEnd())).thenReturn(List.of(payment));
        when(linkRepository.findDistinctByMembers_Transaction_IdIn(anyCollection()))
                .thenReturn(List.of(link(LinkType.CC_PAYMENT, payment, false)));

        CardBill bill = service.build(card, s, TODAY);

        assertEquals(BillStatus.PARTIAL, bill.status());
        assertEquals(new BigDecimal("28250"), bill.remainingAmount());
    }

    @Test
    void unlinkedCreditIsOnlyAPossiblePaymentAndRefundLinksAreNeither() {
        Statement s = statement(card, TODAY.minusDays(10), new BigDecimal("48250"), TODAY.plusDays(18));
        Transaction maybePayment = credit(card, TODAY.minusDays(1), "48250");
        Transaction refund = credit(card, TODAY.minusDays(3), "999");
        Transaction excluded = credit(card, TODAY.minusDays(4), "500");
        excluded.setTransactionExcluded(true);
        when(transactionRepository.findCreditsAfter(card.getId(), s.getPeriodEnd()))
                .thenReturn(List.of(maybePayment, refund, excluded));
        when(linkRepository.findDistinctByMembers_Transaction_IdIn(anyCollection()))
                .thenReturn(List.of(link(LinkType.REFUND, refund, false)));

        CardBill bill = service.build(card, s, TODAY);

        assertEquals(BillStatus.OPEN, bill.status(), "a plain credit never closes the bill");
        assertEquals(0, bill.paidAmount().signum());
        assertEquals(1, bill.possiblePayments().size());
        assertEquals(maybePayment.getId(), bill.possiblePayments().get(0).transactionId());
        assertEquals(new BigDecimal("48250"), bill.possiblePayments().get(0).amount());
    }

    @Test
    void ccPaymentLinkWhereTheCardCreditIsTheAnchorDoesNotCount() {
        Statement s = statement(card, TODAY.minusDays(10), new BigDecimal("48250"), TODAY.plusDays(18));
        Transaction oddCredit = credit(card, TODAY.minusDays(2), "48250");
        when(transactionRepository.findCreditsAfter(card.getId(), s.getPeriodEnd())).thenReturn(List.of(oddCredit));
        when(linkRepository.findDistinctByMembers_Transaction_IdIn(anyCollection()))
                .thenReturn(List.of(link(LinkType.CC_PAYMENT, oddCredit, true)));

        assertEquals(BillStatus.OPEN, service.build(card, s, TODAY).status());
    }

    @Test
    void manualMarkWinsOverLinksFullAndPartial() {
        Statement s = statement(card, TODAY.minusDays(10), new BigDecimal("48250"), TODAY.plusDays(18));
        s.getCreditCardDetails().setPaidMarkedOn(TODAY.minusDays(1));
        assertEquals(BillStatus.PAID, service.build(card, s, TODAY).status());
        assertEquals(PaidSource.MANUAL, service.build(card, s, TODAY).paidSource());

        s.getCreditCardDetails().setPaidMarkedAmount(new BigDecimal("10000"));
        CardBill partial = service.build(card, s, TODAY);
        assertEquals(BillStatus.PARTIAL, partial.status());
        assertEquals(new BigDecimal("38250"), partial.remainingAmount());
    }

    @Test
    void pastDueDateIsOverdueEvenWhenSomethingWasPaid() {
        Statement s = statement(card, TODAY.minusDays(40), new BigDecimal("48250"), TODAY.minusDays(1));
        s.getCreditCardDetails().setPaidMarkedOn(TODAY.minusDays(2));
        s.getCreditCardDetails().setPaidMarkedAmount(new BigDecimal("1000"));

        CardBill bill = service.build(card, s, TODAY);

        assertEquals(BillStatus.OVERDUE, bill.status());
        assertEquals(-1L, bill.daysUntilDue());
        assertEquals(new BigDecimal("47250"), bill.remainingAmount());
    }

    @Test
    void zeroOrCreditBalanceIsNothingDue() {
        Statement s = statement(card, TODAY.minusDays(10), new BigDecimal("-120.00"), TODAY.plusDays(18));
        CardBill bill = service.build(card, s, TODAY);
        assertEquals(BillStatus.NO_DUE, bill.status());
        assertEquals(0, bill.remainingAmount().signum());
    }

    @Test
    void missingDueDateOrTotalIsUnknownUnlessMarkedPaid() {
        Statement noDue = statement(card, TODAY.minusDays(10), new BigDecimal("48250"), null);
        assertEquals(BillStatus.DUE_UNKNOWN, service.build(card, noDue, TODAY).status());
        assertNull(service.build(card, noDue, TODAY).daysUntilDue());

        Statement noTotal = statement(card, TODAY.minusDays(10), null, TODAY.plusDays(5));
        assertEquals(BillStatus.DUE_UNKNOWN, service.build(card, noTotal, TODAY).status());
        assertNull(service.build(card, noTotal, TODAY).remainingAmount());

        noTotal.getCreditCardDetails().setPaidMarkedOn(TODAY);
        assertEquals(BillStatus.PAID, service.build(card, noTotal, TODAY).status());
    }

    @Test
    void utilizationFallsBackToTheAccountLimitAndIsNullWithout() {
        Statement s = statement(card, TODAY.minusDays(10), new BigDecimal("50000"), TODAY.plusDays(18));
        assertEquals(new BigDecimal("25.0"), service.build(card, s, TODAY).digest().utilizationPct());
        s.getCreditCardDetails().setCreditLimit(new BigDecimal("100000"));
        assertEquals(new BigDecimal("50.0"), service.build(card, s, TODAY).digest().utilizationPct());
        card.setCreditCardDetails(null);
        s.getCreditCardDetails().setCreditLimit(null);
        assertNull(service.build(card, s, TODAY).digest().utilizationPct());
    }

    // ---------------------------------------------------------------- choosing the live statement

    @Test
    void latestLiveStatementPrefersTheLatestPeriodEndThenCreation() {
        Statement older = statement(card, LocalDate.of(2026, 8, 10), BigDecimal.TEN, LocalDate.of(2026, 8, 28));
        Statement newer = statement(card, LocalDate.of(2026, 9, 10), BigDecimal.TEN, LocalDate.of(2026, 9, 28));
        Statement undated = statement(card, null, BigDecimal.TEN, null);
        Statement noDetails = new Statement();
        noDetails.setId(UUID.randomUUID());
        noDetails.setPeriodEnd(LocalDate.of(2026, 12, 31));
        when(statementRepository.findQualifyingCreditCardStatements(card.getId()))
                .thenReturn(List.of(older, newer, undated, noDetails));

        assertEquals(newer.getId(), service.latestLiveStatement(card.getId()).orElseThrow().getId());

        when(statementRepository.findQualifyingCreditCardStatements(card.getId())).thenReturn(List.of(undated));
        assertEquals(undated.getId(), service.latestLiveStatement(card.getId()).orElseThrow().getId());

        when(statementRepository.findQualifyingCreditCardStatements(card.getId())).thenReturn(List.of());
        assertTrue(service.latestLiveStatement(card.getId()).isEmpty());
    }

    @Test
    void listBillsSkipsClosedCardsAndCardsWithoutStatementsAndSortsMostUrgentFirst() {
        Account overdueCard = account("Axis");
        Account soonCard = account("ICICI");
        Account closed = account("Old");
        closed.setClosedOn(TODAY.minusDays(1));
        Account closingLater = account("Closing");
        closingLater.setClosedOn(TODAY.plusDays(5));
        Account empty = account("Empty");
        when(accountRepository.findByUserIdAndType(user.getId(), AccountType.credit_card))
                .thenReturn(List.of(soonCard, closed, overdueCard, empty, closingLater));
        // Build first, stub after: the helper stubs findById itself, and Mockito forbids nested stubbing.
        Statement overdueStatement = statement(overdueCard, TODAY.minusDays(30), BigDecimal.TEN, TODAY.minusDays(2));
        Statement soonStatement = statement(soonCard, TODAY.minusDays(10), BigDecimal.TEN, TODAY.plusDays(3));
        Statement closingStatement = statement(closingLater, TODAY.minusDays(10), BigDecimal.TEN, TODAY.plusDays(1));
        Statement closedStatement = statement(closed, TODAY.minusDays(10), BigDecimal.TEN, TODAY);
        when(statementRepository.findQualifyingCreditCardStatements(overdueCard.getId())).thenReturn(List.of(overdueStatement));
        when(statementRepository.findQualifyingCreditCardStatements(soonCard.getId())).thenReturn(List.of(soonStatement));
        when(statementRepository.findQualifyingCreditCardStatements(closingLater.getId())).thenReturn(List.of(closingStatement));
        when(statementRepository.findQualifyingCreditCardStatements(closed.getId())).thenReturn(List.of(closedStatement));

        List<CardBill> bills = service.listBills(user.getId());

        assertEquals(List.of("Axis", "Closing", "ICICI"), bills.stream().map(CardBill::accountName).toList());
    }

    // ---------------------------------------------------------------- writes

    @Test
    void markPaidInFullEndsTheNotificationSequence() {
        Statement s = statement(card, TODAY.minusDays(10), new BigDecimal("48250"), TODAY.plusDays(18));
        s.getCreditCardDetails().setLastNotifiedKind("DUE_7");

        CardBill bill = service.markPaid(user.getId(), s.getId(), null, null);

        assertEquals(BillStatus.PAID, bill.status());
        assertEquals(TODAY, s.getCreditCardDetails().getPaidMarkedOn());
        assertNull(s.getCreditCardDetails().getPaidMarkedAmount());
        assertEquals("PAID", s.getCreditCardDetails().getLastNotifiedKind());
        assertEquals(TODAY, s.getCreditCardDetails().getLastNotifiedOn());
    }

    @Test
    void markPaidPartiallyKeepsRemindersGoing() {
        Statement s = statement(card, TODAY.minusDays(10), new BigDecimal("48250"), TODAY.plusDays(18));
        s.getCreditCardDetails().setLastNotifiedKind("DUE_7");

        CardBill bill = service.markPaid(user.getId(), s.getId(), new BigDecimal("5000"), TODAY.minusDays(1));

        assertEquals(BillStatus.PARTIAL, bill.status());
        assertEquals(TODAY.minusDays(1), bill.paidMarkedOn());
        assertEquals("DUE_7", s.getCreditCardDetails().getLastNotifiedKind());
    }

    @Test
    void markPaidValidatesAmountDateOwnershipAndStatementType() {
        Statement s = statement(card, TODAY.minusDays(10), new BigDecimal("48250"), TODAY.plusDays(18));
        assertThrows(ValidationException.class, () -> service.markPaid(user.getId(), s.getId(), BigDecimal.ZERO, null));
        assertThrows(ValidationException.class, () -> service.markPaid(user.getId(), s.getId(), null, TODAY.plusDays(1)));
        assertThrows(ValidationException.class, () -> service.markPaid(UUID.randomUUID(), s.getId(), null, null));
        assertThrows(ResourceNotFoundException.class, () -> service.markPaid(user.getId(), UUID.randomUUID(), null, null));

        Statement bank = statement(card, TODAY.minusDays(10), new BigDecimal("1"), TODAY);
        bank.setStatementType("bank_account");
        assertThrows(ValidationException.class, () -> service.markPaid(user.getId(), bank.getId(), null, null));
        assertNull(s.getCreditCardDetails().getPaidMarkedOn(), "nothing was written on any failed call");
    }

    @Test
    void unmarkPaidReopensTheBillAndResumesTheSequence() {
        Statement s = statement(card, TODAY.minusDays(10), new BigDecimal("48250"), TODAY.plusDays(18));
        service.markPaid(user.getId(), s.getId(), null, null);

        CardBill bill = service.unmarkPaid(user.getId(), s.getId());

        assertEquals(BillStatus.OPEN, bill.status());
        assertNull(s.getCreditCardDetails().getPaidMarkedOn());
        assertEquals("RECEIVED", s.getCreditCardDetails().getLastNotifiedKind());
    }

    @Test
    void updateDetailsFillsTheGapsAndRestartsTheSequence() {
        Statement s = statement(card, TODAY.minusDays(10), null, null);
        s.getCreditCardDetails().setLastNotifiedKind("DUE_MISSING");

        CardBill bill = service.updateDetails(user.getId(), s.getId(), TODAY.plusDays(4), new BigDecimal("12000"), new BigDecimal("600"));

        assertEquals(BillStatus.OPEN, bill.status());
        assertEquals(4L, bill.daysUntilDue());
        assertEquals(new BigDecimal("600"), bill.minimumAmountDue());
        assertEquals("RECEIVED", s.getCreditCardDetails().getLastNotifiedKind());

        assertThrows(ValidationException.class, () -> service.updateDetails(user.getId(), s.getId(), null, null, null));
        assertThrows(ValidationException.class, () -> service.updateDetails(user.getId(), s.getId(), null, new BigDecimal("-1"), null));
        assertThrows(ValidationException.class, () -> service.updateDetails(user.getId(), s.getId(), null, null, new BigDecimal("-1")));
    }

    @Test
    void updateDetailsLeavesAPaidMarkerAlone() {
        Statement s = statement(card, TODAY.minusDays(10), new BigDecimal("100"), TODAY.plusDays(4));
        s.getCreditCardDetails().setLastNotifiedKind("PAID");
        s.getCreditCardDetails().setPaidMarkedOn(TODAY);
        service.updateDetails(user.getId(), s.getId(), TODAY.plusDays(6), null, null);
        assertEquals("PAID", s.getCreditCardDetails().getLastNotifiedKind());
    }

    @Test
    void findByStatementIdChecksOwnership() {
        Statement s = statement(card, TODAY.minusDays(10), new BigDecimal("100"), TODAY.plusDays(4));
        assertEquals(s.getId(), service.findByStatementId(user.getId(), s.getId()).statementId());
        assertThrows(ValidationException.class, () -> service.findByStatementId(UUID.randomUUID(), s.getId()));
        assertFalse(service.findByStatementId(user.getId(), s.getId()).muted());
        card.setNotificationsMuted(true);
        assertTrue(service.findByStatementId(user.getId(), s.getId()).muted());
    }
}
