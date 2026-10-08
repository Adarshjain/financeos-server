package com.financeos.domain.notification.lending;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.financeos.core.time.AppTime;
import com.financeos.domain.lending.Counterparty;
import com.financeos.domain.lending.Lending;
import com.financeos.domain.lending.LendingDirection;
import com.financeos.domain.lending.LendingKind;
import com.financeos.domain.lending.LendingRepository;
import com.financeos.domain.notification.NotificationKind;
import com.financeos.domain.notification.NotificationOutcome;
import com.financeos.domain.notification.NotificationPrefs;
import com.financeos.domain.notification.NotificationPrefsLoader;
import com.financeos.domain.notification.NotificationSettingsCodec;
import com.financeos.domain.notification.NotificationSettingsService;
import com.financeos.domain.notification.UserNotificationSettings;
import com.financeos.domain.notification.push.PushMessage;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class LendingReturnNotificationServiceTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 20);

    private final LendingRepository repository = mock(LendingRepository.class);
    private final NotificationPrefsLoader prefsLoader = mock(NotificationPrefsLoader.class);
    private final NotificationSettingsService settingsService = mock(NotificationSettingsService.class);
    private final LendingReturnNotificationService service = new LendingReturnNotificationService(repository, prefsLoader, settingsService);

    private final UUID userId = UUID.randomUUID();
    private final UserNotificationSettings settings = new UserNotificationSettings(userId);
    private final Map<NotificationKind, Boolean> kinds = new EnumMap<>(NotificationSettingsCodec.parseKinds(null));
    private final Counterparty rahul = counterparty("Rahul");
    private final List<Lending> ledger = new ArrayList<>();

    @BeforeEach
    void setUp() {
        clockAt(10);
        when(settingsService.deliver(any(), any())).thenReturn(1);
        when(repository.findAllWithRefs()).thenReturn(ledger);
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

    private static Counterparty counterparty(String name) {
        Counterparty cp = new Counterparty();
        cp.setId(UUID.randomUUID());
        cp.setName(name);
        return cp;
    }

    private Lending entry(Counterparty cp, LendingDirection direction, LendingKind kind, String amount, LocalDate entryDate, LocalDate expectedBack) {
        Lending l = new Lending();
        l.setId(UUID.randomUUID());
        l.setCounterparty(cp);
        l.setDirection(direction);
        l.setKind(kind);
        l.setAmount(new BigDecimal(amount));
        l.setEntryDate(entryDate);
        l.setExpectedReturnDate(expectedBack);
        ledger.add(l);
        return l;
    }

    private PushMessage delivered() {
        ArgumentCaptor<PushMessage> message = ArgumentCaptor.forClass(PushMessage.class);
        verify(settingsService).deliver(eq(settings), message.capture());
        return message.getValue();
    }

    // ---------------------------------------------------------------- obligation detection (pure)

    @Test
    void netsEveryEntryAndPicksTheEarliestPrincipalEntryInTheOutstandingDirection() {
        entry(rahul, LendingDirection.lent, LendingKind.principal, "10000", TODAY.minusDays(60), TODAY.minusDays(10));
        Lending later = entry(rahul, LendingDirection.lent, LendingKind.principal, "5000", TODAY.minusDays(30), TODAY.minusDays(2));
        entry(rahul, LendingDirection.borrowed, LendingKind.settlement, "12000", TODAY.minusDays(5), null); // he repaid most of it

        List<LendingReturnNotificationService.Obligation> due = LendingReturnNotificationService.dueObligations(ledger, TODAY);

        assertEquals(1, due.size());
        assertEquals(new BigDecimal("3000"), due.get(0).outstanding());
        assertEquals(LendingDirection.lent, due.get(0).direction());
        assertEquals(ledger.get(0), due.get(0).subject(), "the earliest expected return date sets the obligation");
        assertTrue(later.getExpectedReturnDate().isAfter(ledger.get(0).getExpectedReturnDate()));
    }

    @Test
    void settledCounterpartiesFutureDatesAndDatelessEntriesAreNotObligations() {
        entry(rahul, LendingDirection.lent, LendingKind.principal, "1000", TODAY.minusDays(10), TODAY.minusDays(1));
        entry(rahul, LendingDirection.borrowed, LendingKind.settlement, "1000", TODAY.minusDays(1), null);
        Counterparty priya = counterparty("Priya");
        entry(priya, LendingDirection.lent, LendingKind.principal, "500", TODAY.minusDays(1), TODAY.plusDays(3));
        Counterparty amit = counterparty("Amit");
        entry(amit, LendingDirection.lent, LendingKind.principal, "500", TODAY.minusDays(1), null);

        assertTrue(LendingReturnNotificationService.dueObligations(ledger, TODAY).isEmpty());
    }

    @Test
    void borrowedMoneyIsAnObligationTheOtherWay() {
        entry(rahul, LendingDirection.borrowed, LendingKind.principal, "2000", TODAY.minusDays(20), TODAY);
        List<LendingReturnNotificationService.Obligation> due = LendingReturnNotificationService.dueObligations(ledger, TODAY);
        assertEquals(LendingDirection.borrowed, due.get(0).direction());
        assertEquals(new BigDecimal("2000"), due.get(0).outstanding());
    }

    @Test
    void aSettlementEntryNeverDefinesTheDateEvenWithAReturnDate() {
        entry(rahul, LendingDirection.lent, LendingKind.settlement, "100", TODAY.minusDays(3), TODAY.minusDays(3));
        entry(rahul, LendingDirection.lent, LendingKind.principal, "900", TODAY.minusDays(2), TODAY.plusDays(10));
        assertTrue(LendingReturnNotificationService.dueObligations(ledger, TODAY).isEmpty());
    }

    // ---------------------------------------------------------------- evaluate

    @Test
    void waitsForTheSendHour() {
        clockAt(8);
        entry(rahul, LendingDirection.lent, LendingKind.principal, "1000", TODAY.minusDays(10), TODAY);
        assertEquals(NotificationOutcome.NONE, service.evaluate(userId));
        verify(repository, never()).findAllWithRefs();
    }

    @Test
    void dueTodayIsAnnouncedOnceWithTheShareDeepLink() {
        Lending subject = entry(rahul, LendingDirection.lent, LendingKind.principal, "12000", TODAY.minusDays(30), TODAY);

        assertEquals(new NotificationOutcome(1, 1, 1), service.evaluate(userId));
        PushMessage message = delivered();
        assertEquals("Rahul: ₹12,000 due back today", message.title());
        assertEquals("You lent it on 20 Sep. Tap to share the ledger.", message.body());
        assertEquals("/loans/lendings/" + rahul.getId() + "?export=1", message.url());
        assertEquals("lending-" + rahul.getId(), message.tag());
        assertEquals("DUE_0", subject.getReturnNotifiedKind());
        assertEquals(TODAY, subject.getReturnNotifiedOn());

        assertEquals(new NotificationOutcome(1, 0, 0), service.evaluate(userId), "same day, nothing more");
    }

    @Test
    void overdueFollowsDueTodayThenRepeatsWeekly() {
        Lending subject = entry(rahul, LendingDirection.lent, LendingKind.principal, "12000", TODAY.minusDays(30), TODAY.minusDays(3));
        subject.setReturnNotifiedKind("DUE_0");
        subject.setReturnNotifiedOn(TODAY.minusDays(3));

        assertEquals(new NotificationOutcome(1, 1, 1), service.evaluate(userId));
        PushMessage message = delivered();
        assertEquals("Rahul: ₹12,000 overdue by 3 days", message.title());
        assertEquals("Expected back 17 Oct. Tap to share the ledger.", message.body());
        assertEquals("OVERDUE", subject.getReturnNotifiedKind());

        assertEquals(new NotificationOutcome(1, 0, 0), service.evaluate(userId));
        subject.setReturnNotifiedOn(TODAY.minusDays(7));
        assertEquals(new NotificationOutcome(1, 1, 1), service.evaluate(userId));
    }

    @Test
    void borrowedWordingPointsAtSettlingUpWithoutTheExportParam() {
        entry(rahul, LendingDirection.borrowed, LendingKind.principal, "2000", TODAY.minusDays(20), TODAY.minusDays(1));
        service.evaluate(userId);
        PushMessage message = delivered();
        assertEquals("Rahul: you owe ₹2,000, 1 day late", message.title());
        assertEquals("Was due back 19 Oct. Settle up and record it.", message.body());
        assertEquals("/loans/lendings/" + rahul.getId(), message.url());

        ledger.clear();
        entry(rahul, LendingDirection.borrowed, LendingKind.principal, "2000", TODAY.minusDays(20), TODAY);
        assertEquals("You borrowed it on 30 Sep. Time to settle up.", LendingMessages.forKind("DUE_0", ledger.get(0), "Rahul", rahul.getId(),
                new BigDecimal("2000"), LendingDirection.borrowed, 0).body());
    }

    @Test
    void markerAdvancesWithoutPushWhenPushIsOffOrTheKindIsOff() {
        prefs(false);
        Lending subject = entry(rahul, LendingDirection.lent, LendingKind.principal, "1000", TODAY.minusDays(10), TODAY);
        assertEquals(new NotificationOutcome(1, 1, 0), service.evaluate(userId));
        assertEquals("DUE_0", subject.getReturnNotifiedKind());
        verify(settingsService, never()).deliver(any(), any());

        kinds.put(NotificationKind.LENDING_RETURN, false);
        prefs(true);
        subject.setReturnNotifiedKind(null);
        subject.setReturnNotifiedOn(null);
        assertEquals(new NotificationOutcome(1, 1, 0), service.evaluate(userId));
        verify(settingsService, never()).deliver(any(), any());
    }

    @Test
    void nothingDueIsNoWork() {
        entry(rahul, LendingDirection.lent, LendingKind.principal, "1000", TODAY.minusDays(10), TODAY.plusDays(5));
        assertEquals(NotificationOutcome.NONE, service.evaluate(userId));
        assertNull(ledger.get(0).getReturnNotifiedKind());
    }
}
