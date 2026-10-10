package com.financeos.domain.notification.bill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
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
import com.financeos.domain.statement.Statement;
import com.financeos.domain.statement.StatementCreditCardDetails;
import com.financeos.domain.statement.StatementRepository;
import com.financeos.domain.transaction.TransactionRepository;
import com.financeos.domain.transaction.link.TransactionLinkRepository;
import com.financeos.domain.user.User;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The bill digest's utilisation is the live figure (AccountService), on every read path; build() leaves it null. */
class CardBillLiveUtilizationTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 20);

    private AccountRepository accountRepository;
    private StatementRepository statementRepository;
    private TransactionRepository transactionRepository;
    private AccountService accountService;
    private CardBillService service;
    private User user;
    private Account card;
    private Statement statement;

    @BeforeEach
    void setUp() {
        AppTime.useClock(Clock.fixed(TODAY.atTime(10, 0).atZone(IST).toInstant(), IST));
        accountRepository = mock(AccountRepository.class);
        statementRepository = mock(StatementRepository.class);
        transactionRepository = mock(TransactionRepository.class);
        TransactionLinkRepository linkRepository = mock(TransactionLinkRepository.class);
        accountService = mock(AccountService.class);
        service = new CardBillService(accountRepository, statementRepository, transactionRepository, linkRepository, accountService);
        when(transactionRepository.findCreditsAfter(any(), any())).thenReturn(List.of());
        when(linkRepository.findDistinctByMembers_Transaction_IdIn(anyCollection())).thenReturn(List.of());
        when(statementRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        user = new User();
        user.setId(UUID.randomUUID());
        card = new Account();
        card.setId(UUID.randomUUID());
        card.setUser(user);
        card.setName("Amex");
        card.setType(AccountType.credit_card);
        card.setCreditCardDetails(new AccountCreditCardDetails(card, new BigDecimal("100000"), null));

        statement = new Statement();
        statement.setId(UUID.randomUUID());
        statement.setUser(user);
        statement.setAccount(card);
        statement.setStatementType("credit_card");
        statement.setPeriodStart(TODAY.minusDays(40));
        statement.setPeriodEnd(TODAY.minusDays(10));
        StatementCreditCardDetails d = new StatementCreditCardDetails(statement);
        d.setUser(user);
        d.setTotalAmountDue(new BigDecimal("90000"));
        d.setPaymentDueDate(TODAY.plusDays(8));
        d.setCreditLimit(new BigDecimal("50000"));
        statement.setCreditCardDetails(d);
        when(statementRepository.findById(statement.getId())).thenReturn(Optional.of(statement));
        when(statementRepository.findQualifyingCreditCardStatements(card.getId())).thenReturn(List.of(statement));

        // The live balance: AccountService fills the card's utilisation on the entity it is handed.
        when(accountService.populateLiveBalance(any())).thenAnswer(inv -> {
            Account a = inv.getArgument(0);
            a.setUtilizationPct(new BigDecimal("12.5"));
            return a;
        });
    }

    @AfterEach
    void tearDown() {
        AppTime.reset();
    }

    @Test
    void listBillsReportsTheLiveUtilisationNotTheStatementDue() {
        when(accountRepository.findByUserIdAndType(user.getId(), AccountType.credit_card)).thenReturn(List.of(card));

        CardBill bill = service.listBills(user.getId()).get(0);

        assertEquals(new BigDecimal("12.5"), bill.digest().utilizationPct());
        assertEquals(new BigDecimal("100000"), bill.digest().creditLimit(), "the card's own limit, as utilisation uses");
    }

    @Test
    void findByStatementIdReportsTheLiveUtilisation() {
        assertEquals(new BigDecimal("12.5"), service.findByStatementId(user.getId(), statement.getId()).digest().utilizationPct());
    }

    @Test
    void markPaidAndUnmarkReportTheLiveUtilisation() {
        assertEquals(new BigDecimal("12.5"),
                service.markPaid(user.getId(), statement.getId(), null, null).digest().utilizationPct());
        assertEquals(new BigDecimal("12.5"),
                service.unmarkPaid(user.getId(), statement.getId()).digest().utilizationPct());
    }

    @Test
    void theNotificationBuildSkipsTheLiveBalanceQueries() {
        CardBill bill = service.build(card, statement, TODAY);

        assertNull(bill.digest().utilizationPct());
        verify(accountService, never()).populateLiveBalance(any());
    }

    @Test
    void withUtilizationReplacesOnlyTheDigestPercentage() {
        CardBill bill = service.build(card, statement, TODAY);
        CardBill withPct = bill.withUtilization(new BigDecimal("40.0"));

        assertEquals(new BigDecimal("40.0"), withPct.digest().utilizationPct());
        assertEquals(bill.digest().creditLimit(), withPct.digest().creditLimit());
        assertEquals(bill.status(), withPct.status());
        assertEquals(bill.remainingAmount(), withPct.remainingAmount());
    }

    @Test
    void withUtilizationOnABillWithoutADigestIsANoOp() {
        CardBill bill = service.build(card, statement, TODAY);
        CardBill noDigest = new CardBill(bill.accountId(), bill.accountName(), bill.last4(), bill.statementId(),
                bill.periodStart(), bill.periodEnd(), bill.paymentDueDate(), bill.totalAmountDue(), bill.minimumAmountDue(),
                bill.paidAmount(), bill.remainingAmount(), bill.paidSource(), bill.status(), bill.daysUntilDue(),
                bill.paidMarkedOn(), bill.possiblePayments(), bill.muted(), bill.statementCreatedAt(),
                bill.lastNotifiedKind(), bill.lastNotifiedOn(), null, null, null);
        assertSame(noDigest, noDigest.withUtilization(new BigDecimal("1.0")));
    }
}
