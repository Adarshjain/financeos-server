package com.financeos.domain.notification.statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import com.financeos.domain.notification.NotificationOutcome;
import com.financeos.domain.notification.NotificationPrefs;
import com.financeos.domain.notification.NotificationPrefsLoader;
import com.financeos.domain.notification.NotificationSettingsCodec;
import com.financeos.domain.notification.NotificationSettingsService;
import com.financeos.domain.notification.UserNotificationSettings;
import com.financeos.domain.notification.push.PushMessage;
import com.financeos.domain.statement.Statement;
import com.financeos.domain.statement.StatementRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class StatementExpectedNotificationServiceTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 20);

    private final AccountRepository accountRepository = mock(AccountRepository.class);
    private final StatementRepository statementRepository = mock(StatementRepository.class);
    private final NotificationPrefsLoader prefsLoader = mock(NotificationPrefsLoader.class);
    private final NotificationSettingsService settingsService = mock(NotificationSettingsService.class);
    private final StatementExpectedNotificationService service =
            new StatementExpectedNotificationService(accountRepository, statementRepository, prefsLoader, settingsService);

    private final UUID userId = UUID.randomUUID();
    private final UserNotificationSettings settings = new UserNotificationSettings(userId);
    private final Map<NotificationKind, Boolean> kinds = new EnumMap<>(NotificationSettingsCodec.parseKinds(null));
    private Account card;

    @BeforeEach
    void setUp() {
        clockAt(10);
        card = new Account();
        card.setId(UUID.randomUUID());
        card.setName("HDFC Regalia");
        card.setType(AccountType.credit_card);
        card.setIngestFromDate(LocalDate.of(2026, 1, 1));
        when(accountRepository.findByUserIdAndType(userId, AccountType.credit_card)).thenReturn(List.of(card));
        when(settingsService.deliver(any(), any())).thenReturn(1);
        prefs(true);
    }

    @AfterEach
    void tearDown() {
        AppTime.reset();
    }

    private void clockAt(int hour) {
        AppTime.useClock(Clock.fixed(TODAY.atTime(hour, 5).atZone(IST).toInstant(), IST));
    }

    private void prefs(boolean canSend) {
        when(prefsLoader.load(userId)).thenReturn(new NotificationPrefs(settings, canSend, 9, List.of(7, 3, 1, 0), kinds));
    }

    /** Monthly statements closing on the 15th, the last one ending on {@code lastEnd}. */
    private static List<Statement> monthlyUntil(LocalDate lastEnd, int count) {
        List<Statement> list = new java.util.ArrayList<>();
        for (int i = count - 1; i >= 0; i--) {
            Statement s = new Statement();
            s.setId(UUID.randomUUID());
            LocalDate end = lastEnd.minusMonths(i);
            s.setPeriodEnd(end);
            s.setPeriodStart(end.minusMonths(1).plusDays(1));
            s.setStatementType("credit_card");
            list.add(s);
        }
        return list;
    }

    // ---------------------------------------------------------------- projection (pure)

    @Test
    void noStatementsMeansNothingToExpect() {
        assertNull(StatementExpectedNotificationService.overduePeriodEnd(List.of(), TODAY));
        Statement dateless = new Statement();
        assertNull(StatementExpectedNotificationService.overduePeriodEnd(List.of(dateless), TODAY));
    }

    @Test
    void aCardWithinTheGracePeriodIsCurrent() {
        // Last statement closed 15 Sep → next projected close 15 Oct; grace runs to 20 Oct inclusive.
        assertNull(StatementExpectedNotificationService.overduePeriodEnd(monthlyUntil(LocalDate.of(2026, 9, 15), 3), TODAY));
    }

    @Test
    void oneMissedCycleYieldsThatCyclesEnd() {
        assertEquals(LocalDate.of(2026, 10, 15),
                StatementExpectedNotificationService.overduePeriodEnd(monthlyUntil(LocalDate.of(2026, 9, 15), 3), LocalDate.of(2026, 10, 21)));
    }

    @Test
    void severalMissedCyclesYieldTheLatestOverdueOne() {
        assertEquals(LocalDate.of(2026, 9, 15),
                StatementExpectedNotificationService.overduePeriodEnd(monthlyUntil(LocalDate.of(2026, 7, 15), 2), TODAY));
    }

    // ---------------------------------------------------------------- evaluate

    @Test
    void waitsForTheSendHour() {
        clockAt(8);
        assertEquals(NotificationOutcome.NONE, service.evaluate(userId));
        verify(statementRepository, never()).findQualifyingCreditCardStatements(any());
    }

    @Test
    void announcesTheMissingStatementOnceAndRemembersTheCycle() {
        when(statementRepository.findQualifyingCreditCardStatements(card.getId())).thenReturn(monthlyUntil(LocalDate.of(2026, 8, 15), 3));

        assertEquals(new NotificationOutcome(1, 1, 1), service.evaluate(userId));

        ArgumentCaptor<PushMessage> message = ArgumentCaptor.forClass(PushMessage.class);
        verify(settingsService).deliver(eq(settings), message.capture());
        assertEquals("HDFC Regalia: Sep statement hasn't arrived", message.getValue().title());
        assertEquals("Expected around 15 Sep. Check the mailbox connection, or upload it.", message.getValue().body());
        assertEquals("/inbox?item=statement-expected:" + card.getId() + ":2026-09-15", message.getValue().url());
        assertEquals("statement-expected-" + card.getId(), message.getValue().tag());
        assertEquals(LocalDate.of(2026, 9, 15), card.getStatementExpectedNotifiedFor());
        verify(accountRepository).save(card);

        assertEquals(new NotificationOutcome(1, 0, 0), service.evaluate(userId), "same cycle, no repeat");
    }

    @Test
    void aFurtherMissedCycleIsANewAnnouncement() {
        card.setStatementExpectedNotifiedFor(LocalDate.of(2026, 8, 15));
        when(statementRepository.findQualifyingCreditCardStatements(card.getId())).thenReturn(monthlyUntil(LocalDate.of(2026, 7, 15), 2));

        assertEquals(new NotificationOutcome(1, 1, 1), service.evaluate(userId));
        assertEquals(LocalDate.of(2026, 9, 15), card.getStatementExpectedNotifiedFor());
    }

    @Test
    void manualCardsPointAtTheUploadPage() {
        card.setIngestFromDate(null);
        PushMessage message = StatementExpectedMessages.missing(card, LocalDate.of(2026, 9, 15));
        assertEquals("/inbox?item=statement-expected:" + card.getId() + ":2026-09-15", message.url());
        assertEquals("Expected around 15 Sep. Upload it to keep bills and rewards current.", message.body());
    }

    @Test
    void closedCardsAndCurrentCardsAreSkipped() {
        card.setClosedOn(TODAY.minusDays(1));
        when(statementRepository.findQualifyingCreditCardStatements(card.getId())).thenReturn(monthlyUntil(LocalDate.of(2026, 7, 15), 2));
        assertEquals(NotificationOutcome.NONE, service.evaluate(userId));

        card.setClosedOn(null);
        when(statementRepository.findQualifyingCreditCardStatements(card.getId())).thenReturn(monthlyUntil(LocalDate.of(2026, 9, 15), 3));
        assertEquals(NotificationOutcome.NONE, service.evaluate(userId));
        verify(settingsService, never()).deliver(any(), any());
    }

    @Test
    void kindOffStillRecordsTheCycle() {
        kinds.put(NotificationKind.STATEMENT_EXPECTED, false);
        when(statementRepository.findQualifyingCreditCardStatements(card.getId())).thenReturn(monthlyUntil(LocalDate.of(2026, 8, 15), 3));
        assertEquals(new NotificationOutcome(1, 1, 0), service.evaluate(userId));
        assertEquals(LocalDate.of(2026, 9, 15), card.getStatementExpectedNotifiedFor());
        verify(settingsService, never()).deliver(any(), any());
    }
}
