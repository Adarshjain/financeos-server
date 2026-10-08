package com.financeos.domain.notification.bill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.financeos.core.time.AppTime;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.account.AccountType;
import com.financeos.domain.notification.NotificationKind;
import com.financeos.domain.notification.NotificationSettingsService;
import com.financeos.domain.notification.UserNotificationSettings;
import com.financeos.domain.notification.UserNotificationSettingsRepository;
import com.financeos.domain.notification.push.PushMessage;
import com.financeos.domain.notification.push.WebPushSender;
import com.financeos.domain.statement.Statement;
import com.financeos.domain.statement.StatementCreditCardDetails;
import com.financeos.domain.statement.StatementRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class BillNotificationServiceTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 20);
    private static final List<Integer> OFFSETS = List.of(7, 3, 1, 0);

    private UserNotificationSettingsRepository settingsRepository;
    private NotificationSettingsService settingsService;
    private CardBillService cardBillService;
    private AccountRepository accountRepository;
    private StatementRepository statementRepository;
    private WebPushSender sender;
    private BillNotificationService service;

    private final UUID userId = UUID.randomUUID();
    private Account card;
    private Statement statement;
    private UserNotificationSettings settings;

    @BeforeEach
    void setUp() {
        clockAt(10);
        settingsRepository = mock(UserNotificationSettingsRepository.class);
        settingsService = mock(NotificationSettingsService.class);
        cardBillService = mock(CardBillService.class);
        accountRepository = mock(AccountRepository.class);
        statementRepository = mock(StatementRepository.class);
        sender = mock(WebPushSender.class);
        service = new BillNotificationService(settingsRepository, settingsService, cardBillService,
                accountRepository, statementRepository, sender);

        card = new Account();
        card.setId(UUID.randomUUID());
        card.setName("HDFC");
        card.setType(AccountType.credit_card);
        statement = new Statement();
        statement.setId(UUID.randomUUID());
        statement.setAccount(card);
        statement.setStatementType("credit_card");
        statement.setPeriodEnd(TODAY.minusDays(10));
        statement.setCreatedAt(TODAY.minusDays(9).atStartOfDay(IST).toInstant());
        StatementCreditCardDetails d = new StatementCreditCardDetails(statement);
        statement.setCreditCardDetails(d);

        settings = new UserNotificationSettings(userId);
        settings.setPushSubscriptions("[{\"endpoint\":\"https://push/1\",\"p256dh\":\"k\",\"auth\":\"a\"}]");
        when(settingsRepository.findById(userId)).thenReturn(Optional.of(settings));
        when(sender.isConfigured()).thenReturn(true);
        when(settingsService.deliver(any(), any())).thenReturn(1);
        when(accountRepository.findByUserIdAndType(userId, AccountType.credit_card)).thenReturn(List.of(card));
        when(cardBillService.latestLiveStatement(card.getId())).thenReturn(Optional.of(statement));
        when(statementRepository.findById(statement.getId())).thenReturn(Optional.of(statement));
    }

    @AfterEach
    void tearDown() {
        AppTime.reset();
    }

    private void clockAt(int hour) {
        AppTime.useClock(Clock.fixed(TODAY.atTime(hour, 5).atZone(IST).toInstant(), IST));
    }

    private CardBill bill(BillStatus status, Long daysUntilDue) {
        return bill(status, daysUntilDue, TODAY.plusDays(daysUntilDue == null ? 0 : daysUntilDue), statement.getCreatedAt());
    }

    private CardBill bill(BillStatus status, Long daysUntilDue, LocalDate due, Instant createdAt) {
        BigDecimal total = status == BillStatus.DUE_UNKNOWN ? null : new BigDecimal("1000");
        return new CardBill(card.getId(), "HDFC", "4321", statement.getId(), statement.getPeriodStart(), statement.getPeriodEnd(),
                status == BillStatus.DUE_UNKNOWN ? null : due, total, null, BigDecimal.ZERO, total,
                PaidSource.NONE, status, status == BillStatus.DUE_UNKNOWN ? null : daysUntilDue, null, List.of(),
                Boolean.TRUE.equals(card.getNotificationsMuted()), createdAt, null, null, null);
    }

    private void billIs(CardBill bill) {
        when(cardBillService.build(eq(card), eq(statement), any())).thenReturn(bill);
    }

    private StatementCreditCardDetails details() {
        return statement.getCreditCardDetails();
    }

    // ---------------------------------------------------------------- pure decision logic

    @Test
    void applicableKindFollowsStatusAndPicksTheMostUrgentReachedOffset() {
        assertEquals("PAID", BillNotificationService.applicableKind(bill(BillStatus.PAID, 5L), OFFSETS));
        assertNull(BillNotificationService.applicableKind(bill(BillStatus.NO_DUE, 5L), OFFSETS));
        assertEquals("DUE_MISSING", BillNotificationService.applicableKind(bill(BillStatus.DUE_UNKNOWN, null), OFFSETS));
        assertEquals("OVERDUE", BillNotificationService.applicableKind(bill(BillStatus.OVERDUE, -3L), OFFSETS));
        assertNull(BillNotificationService.applicableKind(bill(BillStatus.OPEN, 20L), OFFSETS));
        assertEquals("DUE_7", BillNotificationService.applicableKind(bill(BillStatus.OPEN, 7L), OFFSETS));
        assertEquals("DUE_7", BillNotificationService.applicableKind(bill(BillStatus.OPEN, 5L), OFFSETS));
        assertEquals("DUE_3", BillNotificationService.applicableKind(bill(BillStatus.OPEN, 2L), OFFSETS));
        assertEquals("DUE_1", BillNotificationService.applicableKind(bill(BillStatus.PARTIAL, 1L), OFFSETS));
        assertEquals("DUE_0", BillNotificationService.applicableKind(bill(BillStatus.OPEN, 0L), OFFSETS));
        assertEquals("DUE_14", BillNotificationService.applicableKind(bill(BillStatus.OPEN, 10L), List.of(14)));
        assertNull(BillNotificationService.applicableDueKind(bill(BillStatus.OVERDUE, -1L), OFFSETS));
    }

    @Test
    void shouldSendOnlyLaterKindsAndOverdueOncePerDay() {
        assertTrue(BillNotificationService.shouldSend("DUE_7", null, null, TODAY));
        assertTrue(BillNotificationService.shouldSend("DUE_3", "DUE_7", TODAY.minusDays(4), TODAY));
        assertFalse(BillNotificationService.shouldSend("DUE_7", "DUE_3", TODAY.minusDays(1), TODAY));
        assertFalse(BillNotificationService.shouldSend("DUE_3", "DUE_3", TODAY.minusDays(1), TODAY));
        assertTrue(BillNotificationService.shouldSend("OVERDUE", "DUE_0", TODAY.minusDays(1), TODAY));
        assertTrue(BillNotificationService.shouldSend("OVERDUE", "OVERDUE", TODAY.minusDays(1), TODAY));
        assertTrue(BillNotificationService.shouldSend("OVERDUE", "OVERDUE", null, TODAY));
        assertFalse(BillNotificationService.shouldSend("OVERDUE", "OVERDUE", TODAY, TODAY));
        assertFalse(BillNotificationService.shouldSend("OVERDUE", "PAID", TODAY.minusDays(1), TODAY));
        assertFalse(BillNotificationService.shouldSend("PAID", "PAID", TODAY.minusDays(1), TODAY));
        assertFalse(BillNotificationService.shouldSend(null, null, null, TODAY));
    }

    @Test
    void staleGuardUsesIngestDateAgainstDueOrPeriodEnd() {
        LocalDate due = LocalDate.of(2026, 9, 1);
        assertFalse(BillNotificationService.isStale(bill(BillStatus.OVERDUE, -49L, due, due.plusDays(7).atTime(23, 59).atZone(IST).toInstant())));
        assertTrue(BillNotificationService.isStale(bill(BillStatus.OVERDUE, -49L, due, due.plusDays(8).atStartOfDay(IST).toInstant())));
        assertFalse(BillNotificationService.isStale(bill(BillStatus.OPEN, 5L, TODAY.plusDays(5), null)));

        CardBill noDue = bill(BillStatus.DUE_UNKNOWN, null, null, statement.getPeriodEnd().plusDays(46).atStartOfDay(IST).toInstant());
        assertTrue(BillNotificationService.isStale(noDue));
        CardBill noDueFresh = bill(BillStatus.DUE_UNKNOWN, null, null, statement.getPeriodEnd().plusDays(2).atStartOfDay(IST).toInstant());
        assertFalse(BillNotificationService.isStale(noDueFresh));
    }

    @Test
    void preferenceMapping() {
        assertEquals(NotificationKind.STATEMENT_RECEIVED, BillNotificationService.preferenceFor("RECEIVED"));
        assertEquals(NotificationKind.STATEMENT_RECEIVED, BillNotificationService.preferenceFor("DUE_MISSING"));
        assertEquals(NotificationKind.BILL_DUE_REMINDER, BillNotificationService.preferenceFor("DUE_3"));
        assertEquals(NotificationKind.BILL_OVERDUE, BillNotificationService.preferenceFor("OVERDUE"));
        assertNull(BillNotificationService.preferenceFor("PAID"));
    }

    // ---------------------------------------------------------------- hourly tick

    @Test
    void tickBeforeTheSendHourDoesNothingAtAll() {
        clockAt(8);
        billIs(bill(BillStatus.OPEN, 2L));

        BillNotificationService.Outcome outcome = service.evaluateUser(userId);

        assertEquals(BillNotificationService.Outcome.NONE, outcome);
        verify(accountRepository, never()).findByUserIdAndType(any(), any());
        assertNull(details().getLastNotifiedKind());
    }

    @Test
    void tickSendsTheReachedReminderAndRecordsIt() {
        billIs(bill(BillStatus.OPEN, 2L));
        details().setLastNotifiedKind("RECEIVED");

        BillNotificationService.Outcome outcome = service.evaluateUser(userId);

        assertEquals(new BillNotificationService.Outcome(1, 1, 1), outcome);
        ArgumentCaptor<PushMessage> message = ArgumentCaptor.forClass(PushMessage.class);
        verify(settingsService).deliver(eq(settings), message.capture());
        assertEquals("HDFC ••4321: bill due in 2 days", message.getValue().title());
        assertEquals("DUE_3", details().getLastNotifiedKind());
        assertEquals(TODAY, details().getLastNotifiedOn());
        verify(statementRepository).save(statement);
    }

    @Test
    void tickIsIdempotentWithinTheDay() {
        billIs(bill(BillStatus.OPEN, 2L));
        details().setLastNotifiedKind("DUE_3");
        details().setLastNotifiedOn(TODAY);

        assertEquals(new BillNotificationService.Outcome(1, 0, 0), service.evaluateUser(userId));
        verify(settingsService, never()).deliver(any(), any());
    }

    @Test
    void catchUpAfterDowntimeSendsOnlyTheMostUrgentKind() {
        billIs(bill(BillStatus.OPEN, 0L));
        assertNull(details().getLastNotifiedKind());

        service.evaluateUser(userId);

        ArgumentCaptor<PushMessage> message = ArgumentCaptor.forClass(PushMessage.class);
        verify(settingsService).deliver(eq(settings), message.capture());
        assertEquals("HDFC ••4321: bill due today", message.getValue().title());
        assertEquals("DUE_0", details().getLastNotifiedKind());
    }

    @Test
    void overdueRepeatsDailyUntilPaid() {
        billIs(bill(BillStatus.OVERDUE, -2L));
        details().setLastNotifiedKind("OVERDUE");
        details().setLastNotifiedOn(TODAY.minusDays(1));

        assertEquals(1, service.evaluateUser(userId).sent());
        assertEquals(TODAY, details().getLastNotifiedOn());
        assertEquals(0, service.evaluateUser(userId).sent(), "second tick the same day sends nothing");
    }

    @Test
    void mutedCardRecordsButNeverSends() {
        card.setNotificationsMuted(true);
        billIs(bill(BillStatus.OPEN, 2L));

        BillNotificationService.Outcome outcome = service.evaluateUser(userId);

        assertEquals(new BillNotificationService.Outcome(1, 1, 0), outcome);
        verify(settingsService, never()).deliver(any(), any());
        assertEquals("DUE_3", details().getLastNotifiedKind());
    }

    @Test
    void disabledKindRecordsButNeverSends() {
        settings.setKindsJson("{\"BILL_DUE_REMINDER\":false}");
        billIs(bill(BillStatus.OPEN, 2L));

        assertEquals(0, service.evaluateUser(userId).sent());
        assertEquals("DUE_3", details().getLastNotifiedKind());

        settings.setKindsJson("{\"BILL_OVERDUE\":false}");
        billIs(bill(BillStatus.OVERDUE, -1L));
        assertEquals(0, service.evaluateUser(userId).sent());
        assertEquals("OVERDUE", details().getLastNotifiedKind());
    }

    @Test
    void userWithoutSettingsOrPushRecordsButCannotBeReached() {
        when(settingsRepository.findById(userId)).thenReturn(Optional.empty());
        billIs(bill(BillStatus.OPEN, 2L));
        assertEquals(new BillNotificationService.Outcome(1, 1, 0), service.evaluateUser(userId));
        assertEquals("DUE_3", details().getLastNotifiedKind());

        details().setLastNotifiedKind(null);
        when(settingsRepository.findById(userId)).thenReturn(Optional.of(settings));
        when(sender.isConfigured()).thenReturn(false);
        assertEquals(0, service.evaluateUser(userId).sent());
        assertEquals("DUE_3", details().getLastNotifiedKind());

        details().setLastNotifiedKind(null);
        when(sender.isConfigured()).thenReturn(true);
        settings.setPushEnabled(false);
        assertEquals(0, service.evaluateUser(userId).sent());
        verify(settingsService, never()).deliver(any(), any());
    }

    @Test
    void staleBackfilledStatementIsSkippedWithoutTouchingTheMarker() {
        LocalDate due = TODAY.minusDays(60);
        billIs(bill(BillStatus.OVERDUE, -60L, due, TODAY.minusDays(1).atStartOfDay(IST).toInstant()));

        assertEquals(new BillNotificationService.Outcome(1, 0, 0), service.evaluateUser(userId));
        assertNull(details().getLastNotifiedKind());
        verify(settingsService, never()).deliver(any(), any());
    }

    @Test
    void closedCardsAndCardsWithoutStatementsAreSkipped() {
        card.setClosedOn(TODAY);
        assertEquals(BillNotificationService.Outcome.NONE, service.evaluateUser(userId));
        card.setClosedOn(null);
        when(cardBillService.latestLiveStatement(card.getId())).thenReturn(Optional.empty());
        assertEquals(BillNotificationService.Outcome.NONE, service.evaluateUser(userId));
    }

    @Test
    void paidViaLinkRecordsPaidMarkerWithoutAPush() {
        billIs(bill(BillStatus.PAID, 5L));
        details().setLastNotifiedKind("DUE_7");

        assertEquals(new BillNotificationService.Outcome(1, 1, 0), service.evaluateUser(userId));
        assertEquals("PAID", details().getLastNotifiedKind());
        verify(settingsService, never()).deliver(any(), any());
    }

    @Test
    void paidMarkerLeftByADeletedLinkIsResetSoRemindersResume() {
        billIs(bill(BillStatus.OPEN, 2L));
        details().setLastNotifiedKind("PAID");

        assertEquals(1, service.evaluateUser(userId).sent());
        assertEquals("DUE_3", details().getLastNotifiedKind());
    }

    @Test
    void manualPaidMarkerIsNeverReset() {
        CardBill manual = new CardBill(card.getId(), "HDFC", "4321", statement.getId(), null, statement.getPeriodEnd(),
                TODAY.plusDays(2), new BigDecimal("1000"), null, new BigDecimal("1000"), BigDecimal.ZERO,
                PaidSource.MANUAL, BillStatus.PAID, 2L, TODAY, List.of(), false, statement.getCreatedAt(), "PAID", TODAY, null);
        billIs(manual);
        details().setLastNotifiedKind("PAID");

        assertEquals(new BillNotificationService.Outcome(1, 0, 0), service.evaluateUser(userId));
        assertEquals("PAID", details().getLastNotifiedKind());
    }

    @Test
    void nothingDueAndDueUnknownBehave() {
        billIs(bill(BillStatus.NO_DUE, 5L));
        assertEquals(new BillNotificationService.Outcome(1, 0, 0), service.evaluateUser(userId));

        billIs(bill(BillStatus.DUE_UNKNOWN, null));
        assertEquals(1, service.evaluateUser(userId).sent());
        assertEquals("DUE_MISSING", details().getLastNotifiedKind());
        assertEquals(0, service.evaluateUser(userId).sent(), "nudged once only");
    }

    // ---------------------------------------------------------------- statement received

    @Test
    void newStatementSendsTheDigestAndPreAdvancesPastTheReminderItCovers() {
        billIs(bill(BillStatus.OPEN, 5L));

        BillNotificationService.Outcome outcome = service.onStatementCreated(userId, statement.getId());

        assertEquals(new BillNotificationService.Outcome(1, 1, 1), outcome);
        ArgumentCaptor<PushMessage> message = ArgumentCaptor.forClass(PushMessage.class);
        verify(settingsService).deliver(eq(settings), message.capture());
        assertTrue(message.getValue().title().startsWith("HDFC ••4321: ₹1,000 due"));
        assertEquals("DUE_7", details().getLastNotifiedKind(), "the digest already says when it is due");

        assertEquals(new BillNotificationService.Outcome(1, 0, 0), service.evaluateUser(userId), "no second nudge today");
    }

    @Test
    void newStatementFarFromDueRecordsReceivedOnly() {
        billIs(bill(BillStatus.OPEN, 20L));
        service.onStatementCreated(userId, statement.getId());
        assertEquals("RECEIVED", details().getLastNotifiedKind());
    }

    @Test
    void newStatementWithoutDueDateSendsTheMissingNudge() {
        billIs(bill(BillStatus.DUE_UNKNOWN, null));
        service.onStatementCreated(userId, statement.getId());
        ArgumentCaptor<PushMessage> message = ArgumentCaptor.forClass(PushMessage.class);
        verify(settingsService).deliver(eq(settings), message.capture());
        assertEquals("HDFC ••4321: statement imported", message.getValue().title());
        assertEquals("DUE_MISSING", details().getLastNotifiedKind());
    }

    @Test
    void newStatementIgnoresTheHourGateButRespectsPreferenceMuteAndPush() {
        clockAt(6);
        billIs(bill(BillStatus.OPEN, 20L));
        assertEquals(1, service.onStatementCreated(userId, statement.getId()).sent());

        details().setLastNotifiedKind(null);
        settings.setKindsJson("{\"STATEMENT_RECEIVED\":false}");
        assertEquals(new BillNotificationService.Outcome(1, 1, 0), service.onStatementCreated(userId, statement.getId()));
        assertEquals("RECEIVED", details().getLastNotifiedKind());

        details().setLastNotifiedKind(null);
        settings.setKindsJson(null);
        card.setNotificationsMuted(true);
        billIs(bill(BillStatus.OPEN, 20L));
        assertEquals(0, service.onStatementCreated(userId, statement.getId()).sent());
    }

    @Test
    void newStatementThatIsNotTheCurrentBillOrNotACardIsIgnored() {
        Statement newer = new Statement();
        newer.setId(UUID.randomUUID());
        when(cardBillService.latestLiveStatement(card.getId())).thenReturn(Optional.of(newer));
        assertEquals(BillNotificationService.Outcome.NONE, service.onStatementCreated(userId, statement.getId()));

        when(cardBillService.latestLiveStatement(card.getId())).thenReturn(Optional.of(statement));
        statement.setStatementType("bank_account");
        assertEquals(BillNotificationService.Outcome.NONE, service.onStatementCreated(userId, statement.getId()));

        statement.setStatementType("credit_card");
        card.setClosedOn(TODAY.minusDays(1));
        assertEquals(BillNotificationService.Outcome.NONE, service.onStatementCreated(userId, statement.getId()));

        card.setClosedOn(null);
        assertEquals(BillNotificationService.Outcome.NONE, service.onStatementCreated(userId, UUID.randomUUID()));
        verify(settingsService, never()).deliver(any(), any());
    }

    @Test
    void newStatementAlreadyNotifiedIsNotRepeated() {
        billIs(bill(BillStatus.OPEN, 20L));
        details().setLastNotifiedKind("RECEIVED");
        assertEquals(new BillNotificationService.Outcome(1, 0, 0), service.onStatementCreated(userId, statement.getId()));
        verify(settingsService, never()).deliver(any(), any());
    }

    @Test
    void newStaleStatementIsSilent() {
        LocalDate due = TODAY.minusDays(30);
        billIs(bill(BillStatus.OVERDUE, -30L, due, TODAY.atStartOfDay(IST).toInstant()));
        assertEquals(BillNotificationService.Outcome.NONE, service.onStatementCreated(userId, statement.getId()));
        assertNull(details().getLastNotifiedKind());
    }
}
