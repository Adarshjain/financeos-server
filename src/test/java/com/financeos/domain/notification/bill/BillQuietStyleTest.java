package com.financeos.domain.notification.bill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.financeos.core.time.AppTime;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.account.AccountType;
import com.financeos.domain.notification.NotificationSettingsService;
import com.financeos.domain.notification.ReminderStyle;
import com.financeos.domain.notification.UserNotificationSettings;
import com.financeos.domain.notification.UserNotificationSettingsRepository;
import com.financeos.domain.notification.push.WebPushSender;
import com.financeos.domain.statement.Statement;
import com.financeos.domain.statement.StatementCreditCardDetails;
import com.financeos.domain.statement.StatementRepository;
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

/** The Quiet reminder style on the card-bill push: one push, no overdue re-nags. */
class BillQuietStyleTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 20);

    private final UserNotificationSettingsRepository settingsRepository = mock(UserNotificationSettingsRepository.class);
    private final NotificationSettingsService settingsService = mock(NotificationSettingsService.class);
    private final CardBillService cardBillService = mock(CardBillService.class);
    private final AccountRepository accountRepository = mock(AccountRepository.class);
    private final StatementRepository statementRepository = mock(StatementRepository.class);
    private final WebPushSender sender = mock(WebPushSender.class);
    private final BillNotificationService service = new BillNotificationService(settingsRepository, settingsService,
            cardBillService, accountRepository, statementRepository, sender);

    private final UUID userId = UUID.randomUUID();
    private Account card;
    private Statement statement;
    private UserNotificationSettings settings;

    @BeforeEach
    void setUp() {
        AppTime.useClock(Clock.fixed(TODAY.atTime(10, 5).atZone(IST).toInstant(), IST));
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
        statement.setCreditCardDetails(new StatementCreditCardDetails(statement));
        settings = new UserNotificationSettings(userId);
        settings.setReminderStyle(ReminderStyle.QUIET.name());
        settings.setPushSubscriptions("[{\"endpoint\":\"https://push/1\",\"p256dh\":\"k\",\"auth\":\"a\"}]");
        when(settingsRepository.findById(userId)).thenReturn(Optional.of(settings));
        when(sender.isConfigured()).thenReturn(true);
        when(settingsService.deliver(any(), any())).thenReturn(1);
        when(accountRepository.findByUserIdAndType(userId, AccountType.credit_card)).thenReturn(List.of(card));
        when(cardBillService.latestLiveStatement(card.getId())).thenReturn(Optional.of(statement));
    }

    @AfterEach
    void tearDown() {
        AppTime.reset();
    }

    private void billIs(BillStatus status, long daysUntilDue) {
        BigDecimal total = new BigDecimal("1000");
        CardBill bill = new CardBill(card.getId(), "HDFC", "4321", statement.getId(), statement.getPeriodStart(), statement.getPeriodEnd(),
                TODAY.plusDays(daysUntilDue), total, null, BigDecimal.ZERO, total, PaidSource.NONE, status, daysUntilDue, null,
                false, statement.getCreatedAt(), null, null, null, null, null);
        when(cardBillService.build(eq(card), eq(statement), any())).thenReturn(bill);
    }

    @Test
    void aBillStillFiveDaysOutIsNotPushedAndNothingIsRecorded() {
        billIs(BillStatus.OPEN, 5);

        BillNotificationService.Outcome outcome = service.evaluateUser(userId);

        assertEquals(new BillNotificationService.Outcome(1, 0, 0), outcome);
        verify(settingsService, never()).deliver(any(), any());
    }

    @Test
    void theOneDayOffsetPushesOnceAndLaterTicksStaySilent() {
        billIs(BillStatus.OPEN, 1);
        assertEquals(1, service.evaluateUser(userId).sent());
        assertEquals("DUE_1", statement.getCreditCardDetails().getLastNotifiedKind());

        billIs(BillStatus.OPEN, 0);
        assertEquals(0, service.evaluateUser(userId).sent(), "the due day does not push again");

        verify(settingsService, times(1)).deliver(any(), any());
    }

    @Test
    void aBillFirstSeenOnTheDueDayStillGetsItsOnePush() {
        billIs(BillStatus.OPEN, 0);

        assertEquals(1, service.evaluateUser(userId).sent());
        verify(settingsService, times(1)).deliver(any(), any());
    }

    @Test
    void overdueNeverPushesButTheMarkerStillAdvances() {
        billIs(BillStatus.OVERDUE, -3);

        BillNotificationService.Outcome outcome = service.evaluateUser(userId);

        assertEquals(new BillNotificationService.Outcome(1, 1, 0), outcome);
        assertEquals("OVERDUE", statement.getCreditCardDetails().getLastNotifiedKind());
        verify(settingsService, never()).deliver(any(), any());
    }

    @Test
    void standardStyleKeepsTheFullSequenceForComparison() {
        settings.setReminderStyle(ReminderStyle.STANDARD.name());
        billIs(BillStatus.OVERDUE, -3);

        assertEquals(1, service.evaluateUser(userId).sent());
    }
}
